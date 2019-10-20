/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.mxnet.engine;

import ai.djl.Device;
import ai.djl.metric.Metrics;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataDesc;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Parameter;
import ai.djl.training.GradientCollector;
import ai.djl.training.LocalParameterServer;
import ai.djl.training.ParameterServer;
import ai.djl.training.ParameterStore;
import ai.djl.training.Trainer;
import ai.djl.training.TrainingConfig;
import ai.djl.training.TrainingListener;
import ai.djl.training.dataset.Batch;
import ai.djl.training.loss.Loss;
import ai.djl.training.metrics.Accuracy;
import ai.djl.training.metrics.TrainingMetric;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MxTrainer implements Trainer {

    private static final Logger logger = LoggerFactory.getLogger(MxTrainer.class);

    private MxModel model;
    private MxNDManager manager;
    // this is performance metrics similar to predictor metrics, not training accuracy or loss
    // currently not implemented
    private Metrics metrics;
    private TrainingListener listener;
    private Device[] devices;
    private ParameterStore parameterStore;
    private List<TrainingMetric> trainingMetrics;
    private List<TrainingMetric> validateMetrics;
    private Loss trainingLoss;
    private Loss validationLoss;
    private Accuracy trainingAccuracy;
    private Accuracy validationAccuracy;

    private boolean gradientsChecked;

    MxTrainer(MxModel model, TrainingConfig trainingConfig) {
        this.model = model;
        manager = (MxNDManager) model.getNDManager().newSubManager();
        devices = trainingConfig.getDevices();
        trainingMetrics = new ArrayList<>(trainingConfig.getTrainingMetrics());
        validateMetrics = new ArrayList<>();
        trainingMetrics.forEach(i -> validateMetrics.add(i.duplicate()));
        trainingLoss = getTrainingMetric(Loss.class);
        trainingAccuracy = getTrainingMetric(Accuracy.class);
        validationLoss = getValidationMetric(Loss.class);
        validationAccuracy = getValidationMetric(Accuracy.class);

        // ParameterServer parameterServer = new MxParameterServer(trainingConfig.getOptimizer());
        ParameterServer parameterServer = new LocalParameterServer(trainingConfig.getOptimizer());

        parameterStore = new ParameterStore(manager, false);
        parameterStore.setParameterServer(parameterServer, devices);
    }

    @Override
    public void initialize(DataDesc[] inputDescriptor) {
        Shape[] shapes =
                Arrays.stream(inputDescriptor).map(DataDesc::getShape).toArray(Shape[]::new);
        model.getBlock().initialize(model.getNDManager(), model.getDataType(), devices, shapes);
    }

    @Override
    public GradientCollector newGradientCollector() {
        return new MxGradientCollector();
    }

    @Override
    public void train(Batch batch) {
        long begin = System.nanoTime();
        Batch[] splits = batch.split(devices, false);
        try (GradientCollector collector = new MxGradientCollector()) {
            for (Batch split : splits) {
                NDList data = split.getData();
                NDList labels = split.getLabels();

                NDList preds = forward(data);
                trainingMetrics.forEach(metrics -> metrics.update(labels, preds));

                collector.backward(trainingLoss.getLastUpdate());
            }
        }
        addMetric("train", begin);

        if (listener != null) {
            listener.onTrainingBatch();
        }
    }

    @Override
    public NDList forward(NDList input) {
        long begin = System.nanoTime();
        try {
            return model.getBlock().forward(parameterStore, input);
        } finally {
            addMetric("forward", begin);
        }
    }

    @Override
    public void validate(Batch batch) {
        long begin = System.nanoTime();
        Batch[] splits = batch.split(devices, false);
        for (Batch split : splits) {
            NDList data = split.getData();
            NDList labels = split.getLabels();

            NDList preds = forward(data);
            validateMetrics.forEach(metrics -> metrics.update(labels, preds));
        }
        addMetric("validate", begin);

        if (listener != null) {
            listener.onValidationBatch();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void step() {
        if (!gradientsChecked) {
            checkGradients();
        }

        long begin = System.nanoTime();
        parameterStore.updateAllParameters();
        addMetric("step", begin);
    }

    @Override
    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void setTrainingListener(TrainingListener listener) {
        this.listener = listener;
    }

    @Override
    public void resetTrainingMetrics() {
        if (trainingLoss != null) {
            addMetric("train", trainingLoss);
        }
        if (trainingAccuracy != null) {
            addMetric("train", trainingAccuracy);
        }
        if (validationLoss != null) {
            addMetric("validate", validationLoss);
        }
        if (validationAccuracy != null) {
            addMetric("validate", validationAccuracy);
        }

        trainingMetrics.forEach(TrainingMetric::reset);
        validateMetrics.forEach(TrainingMetric::reset);

        if (listener != null) {
            listener.onEpoch();
        }
    }

    public Metrics getMetrics() {
        return metrics;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final <T extends TrainingMetric> T getTrainingMetric(Class<T> clazz) {
        for (TrainingMetric metric : trainingMetrics) {
            if (clazz.isInstance(metric)) {
                return (T) metric;
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends TrainingMetric> T getValidationMetric(Class<T> clazz) {
        for (TrainingMetric metric : validateMetrics) {
            if (clazz.isInstance(metric)) {
                return (T) metric;
            }
        }
        return null;
    }

    @Override
    public NDManager getManager() {
        return manager;
    }

    /**
     * Check if all gradients are zeros, prevent users from calling step() without running {@code
     * backward}.
     */
    private void checkGradients() {
        List<NDArray> grads = new ArrayList<>();
        model.getBlock()
                .getParameters()
                .values()
                .stream()
                .filter(Parameter::requireGradient)
                .forEach(param -> grads.add(parameterStore.getValue(param, devices[0])));

        NDList list = new NDList(grads.stream().map(NDArray::sum).toArray(NDArray[]::new));
        NDArray gradSum = NDArrays.stack(list);
        list.close();

        NDArray array = gradSum.sum();

        float[] sums = array.toFloatArray();

        array.close();
        gradSum.close();

        float sum = 0f;
        for (float num : sums) {
            sum += num;
        }
        if (sum == 0f) {
            throw new IllegalStateException(
                    "Gradient values are all zeros, please call gradientCollector.backward() on"
                            + "your target NDArray (usually loss), before calling step() ");
        }

        gradientsChecked = true;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    @Override
    protected void finalize() throws Throwable {
        if (manager.isOpen()) {
            if (logger.isDebugEnabled()) {
                logger.warn("Model was not closed explicitly: {}", getClass().getSimpleName());
            }
            close();
        }
        super.finalize();
    }

    @Override
    public void close() {
        parameterStore.sync();
        manager.close();
    }

    private void addMetric(String metricName, long begin) {
        if (metrics != null) {
            metrics.addMetric(metricName, System.nanoTime() - begin);
        }
    }

    private void addMetric(String stage, TrainingMetric metric) {
        if (metrics != null) {
            metrics.addMetric(stage + '_' + metric.getName(), metric.getValue());
        }
    }
}