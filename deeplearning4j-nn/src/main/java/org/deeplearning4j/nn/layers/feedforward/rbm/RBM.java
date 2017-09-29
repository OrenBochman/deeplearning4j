/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.layers.feedforward.rbm;

import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.layers.BasePretrainNetwork;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.nn.params.PretrainParamInitializer;
import org.deeplearning4j.optimize.api.TrainingListener;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.rng.distribution.Distribution;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Pair;

import static org.nd4j.linalg.ops.transforms.Transforms.*;


/**
 * Restricted Boltzmann Machine.
 *
 * Markov chain with gibbs sampling.
 *
 * Supports the following visible units:
 *
 *     binary
 *     gaussian
 *     softmax
 *     linear
 *
 * Supports the following hidden units:
 *     rectified
 *     binary
 *     gaussian
 *     softmax
 *     linear
 *
 * Based on Hinton et al.'s work
 *
 * Great reference:
 * http://www.iro.umontreal.ca/~lisa/publications2/index.php/publications/show/239
 *
 *
 * @author Adam Gibson
 *
 * @deprecated Use {@link org.deeplearning4j.nn.conf.layers.variational} instead
 */
@Deprecated
public class RBM extends BasePretrainNetwork<org.deeplearning4j.nn.conf.layers.RBM> {

    private long seed;

    public RBM(org.deeplearning4j.nn.conf.layers.RBM conf) {
        super(conf);
        this.seed = conf.getSeed();
    }

    /**
     *
     */
    //variance matrices for gaussian visible/hidden units
    @Deprecated
    protected INDArray sigma, hiddenSigma;


    @Override
    public Activations getLabels() {
        return null;    //No labels for pretrain layers
    }

    @Override
    public Pair<Gradients, Double> computeGradientAndScore(org.nd4j.linalg.dataset.api.DataSet dataSet) {
        return computeGradientAndScore(
                ActivationsFactory.getInstance().featuresAsActivations(dataSet),
                ActivationsFactory.getInstance().labelsAsActivations(dataSet));
    }

    @Override
    public Pair<Gradients, Double> computeGradientAndScore(MultiDataSet dataSet) {
        return computeGradientAndScore(
                ActivationsFactory.getInstance().featuresAsActivations(dataSet),
                ActivationsFactory.getInstance().labelsAsActivations(dataSet));
    }

    @Override
    public Pair<Gradients,Double> computeGradientAndScore(Activations input, Activations labels) {
        setInput(input);
        applyPreprocessorIfNecessary(true);
        int k = layerConf().getK();

        //POSITIVE PHASE
        // hprob0, hstate0
        Pair<INDArray, INDArray> probHidden = sampleHiddenGivenVisible(input.get(0));

        /*
         * Start the gibbs sampling.
         */
        //        INDArray chainStart = probHidden.getSecond();
        INDArray chainStart = probHidden.getFirst();

        /*
         * Note that at a later date, we can explore alternative methods of
         * storing the chain transitions for different kinds of sampling
         * and exploring the search space.
         */
        Pair<Pair<INDArray, INDArray>, Pair<INDArray, INDArray>> matrices;
        //negative value samples
        INDArray negVProb = null;
        //negative value samples
        INDArray negVSamples = null;
        //negative hidden means or expected values
        INDArray negHProb = null;
        //negative hidden samples
        INDArray negHSamples = null;

        /*
         * K steps of gibbs sampling. This is the positive phase of contrastive divergence.
         *
         * There are 4 matrices being computed for each gibbs sampling.
         * The samples from both the positive and negative phases and their expected values
         * or averages.
         *
         */

        for (int i = 0; i < k; i++) {

            //NEGATIVE PHASE
            if (i == 0)
                matrices = gibbhVh(chainStart);
            else
                matrices = gibbhVh(negHSamples);

            //get the cost updates for sampling in the chain after k iterations
            negVProb = matrices.getFirst().getFirst();
            negVSamples = matrices.getFirst().getSecond();
            negHProb = matrices.getSecond().getFirst();
            negHSamples = matrices.getSecond().getSecond();
        }

        /*
         * Update gradient parameters - note taking mean based on batchsize is handled in LayerUpdater
         */
        INDArray wGradient = input.get(0).transposei().mmul(probHidden.getFirst()).subi(negVProb.transpose().mmul(negHProb));

        INDArray hBiasGradient;

        if (layerConf().getSparsity() != 0)
            //all hidden units must stay around this number
            hBiasGradient = probHidden.getFirst().rsub(layerConf().getSparsity()).sum(0);
        else
            //update rule: the expected values of the hidden input - the negative hidden  means adjusted by the learning rate
            hBiasGradient = probHidden.getFirst().sub(negHProb).sum(0);

        //update rule: the expected values of the input - the negative samples adjusted by the learning rate
        INDArray delta = input.get(0).sub(negVProb);
        INDArray vBiasGradient = delta.sum(0);

        if (conf.isPretrain()) {
            wGradient.negi();
            hBiasGradient.negi();
            vBiasGradient.negi();
        }

        Gradient gradient = createGradient(wGradient, vBiasGradient, hBiasGradient);

        setScoreWithZ(negVSamples); // this is compared to input on

        Pair<Gradients,Double> p = new Pair<>(GradientsFactory.getInstance().create(gradient, null), score);
        if (trainingListeners != null && trainingListeners.size() > 0) {
            for (TrainingListener tl : trainingListeners) {
                tl.onBackwardPass(this, p.getFirst());
            }
        }

        return p;
    }

    @Override
    public void fit(DataSetIterator iter) {
        while(iter.hasNext()){
            fit(iter.next());
        }
    }

    @Override
    public void fit(INDArray examples, INDArray labels) {
        Activations a = ActivationsFactory.getInstance().create(examples);
        fit(a);
        ActivationsFactory.getInstance().release(a);
    }

    @Override
    public void fit(DataSet data) {
        fit(data.getFeatures(), null);
    }

    /**
     * Gibbs sampling step: hidden ---> visible ---> hidden
     *
     * @param h the hidden input
     * @return the expected values and samples of both the visible samples given the hidden
     * and the new hidden input and expected values
     */
    public Pair<Pair<INDArray, INDArray>, Pair<INDArray, INDArray>> gibbhVh(INDArray h) {
        Pair<INDArray, INDArray> v1MeanAndSample = sampleVisibleGivenHidden(h);
        INDArray negVProb = v1MeanAndSample.getFirst();

        Pair<INDArray, INDArray> h1MeanAndSample = sampleHiddenGivenVisible(negVProb);
        return new Pair<>(v1MeanAndSample, h1MeanAndSample);
    }

    /**
     * Binomial sampling of the hidden values given visible
     *
     * @param v the visible values
     * @return a binomial distribution containing the expected values and the samples
     */
    @Override
    public Pair<INDArray, INDArray> sampleHiddenGivenVisible(INDArray v) {
        INDArray hProb = propUp(v);
        INDArray hSample;
        Distribution dist;

        switch (layerConf().getHiddenUnit()) {
            case IDENTITY: {
                hSample = hProb;
                break;
            }
            case BINARY: {
                dist = Nd4j.getDistributions().createBinomial(1, hProb);
                hSample = dist.sample(hProb.shape());
                break;
            }
            case GAUSSIAN: {
                dist = Nd4j.getDistributions().createNormal(hProb, 1);
                hSample = dist.sample(hProb.shape());
                break;
            }
            case RECTIFIED: {
                INDArray sigH1Mean = sigmoid(hProb);
                /*
                 * Rectified linear part
                 */
                INDArray sqrtSigH1Mean = sqrt(sigH1Mean);
                INDArray sample = Nd4j.getDistributions().createNormal(hProb, 1).sample(hProb.shape());
                sample.muli(sqrtSigH1Mean);
                hSample = hProb.add(sample);
                hSample = max(hSample, 0.0);
                break;
            }
            case SOFTMAX: {
                hSample = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform("softmax", hProb));
                break;
            }
            default:
                throw new IllegalStateException(
                                "Hidden unit type must either be Binary, Gaussian, SoftMax or Rectified " + layerId());
        }

        return new Pair<>(hProb, hSample);
    }

    /**
     * Guess the visible values given the hidden
     *
     * @param h the hidden units
     * @return a visible mean and sample relative to the hidden states
     * passed in
     */
    @Override
    public Pair<INDArray, INDArray> sampleVisibleGivenHidden(INDArray h) {
        INDArray vProb = propDown(h);
        INDArray vSample;

        switch (layerConf().getVisibleUnit()) {
            case IDENTITY: {
                vSample = vProb;
                break;
            }
            case BINARY: {
                Distribution dist = Nd4j.getDistributions().createBinomial(1, vProb);
                vSample = dist.sample(vProb.shape());
                break;
            }
            case GAUSSIAN:
            case LINEAR: {
                Distribution dist = Nd4j.getDistributions().createNormal(vProb, 1);
                vSample = dist.sample(vProb.shape());
                break;
            }
            case SOFTMAX: {
                vSample = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform("softmax", vProb));
                break;
            }
            default: {
                throw new IllegalStateException(
                                "Visible type must be one of Binary, Gaussian, SoftMax or Linear " + layerId());
            }
        }

        return new Pair<>(vProb, vSample);

    }

    public INDArray preOutput(INDArray v, boolean training) {
        INDArray weights = getParamWithNoise(PretrainParamInitializer.WEIGHT_KEY, training);
        INDArray bias = getParamWithNoise(PretrainParamInitializer.BIAS_KEY, training);
        return v.mmul(weights).addiRowVector(bias);
    }

    /**
     * Calculates the activation of the visible :
     * sigmoid(v * W + hbias)
     * @param v the visible layer
     * @return the approximated activations of the visible layer
     */
    public INDArray propUp(INDArray v) {
        return propUp(v, true);
    }

    /**
     * Calculates the activation of the visible :
     * sigmoid(v * W + hbias)
     * @param v the visible layer
     * @return the approximated activations of the visible layer
     */
    public INDArray propUp(INDArray v, boolean training) {
        INDArray preSig = preOutput(v, training);

        switch (layerConf().getHiddenUnit()) {
            case IDENTITY:
                return preSig;
            case BINARY:
                return sigmoid(preSig);
            case GAUSSIAN:
                Distribution dist = Nd4j.getDistributions().createNormal(preSig, 1);
                preSig = dist.sample(preSig.shape());
                return preSig;
            case RECTIFIED:
                preSig = max(preSig, 0.0);
                return preSig;
            case SOFTMAX:
                return Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform("softmax", preSig));
            default:
                throw new IllegalStateException(
                                "Hidden unit type should either be binary, gaussian, or rectified linear " + layerId());
        }

    }

    public INDArray propUpDerivative(INDArray z) {
        switch (layerConf().getHiddenUnit()) {
            case IDENTITY:
                return Nd4j.getExecutioner()
                                .execAndReturn(Nd4j.getOpFactory().createTransform("identity", z).derivative());
            case BINARY:
                return Nd4j.getExecutioner()
                                .execAndReturn(Nd4j.getOpFactory().createTransform("sigmoid", z).derivative());
            case GAUSSIAN: {
                Distribution dist = Nd4j.getDistributions().createNormal(z, 1);
                INDArray gaussian = dist.sample(z.shape());
                INDArray derivative = z.mul(-2).mul(gaussian);
                return derivative;
            }
            case RECTIFIED:
                return Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform("relu", z).derivative());
            case SOFTMAX:
                return Nd4j.getExecutioner()
                                .execAndReturn(Nd4j.getOpFactory().createTransform("softmax", z).derivative());
            default:
                throw new IllegalStateException(
                                "Hidden unit type should either be binary, gaussian, or rectified linear " + layerId());
        }

    }

    /**
     * Calculates the activation of the hidden:
     * activation(h * W + vbias)
     * @param h the hidden layer
     * @return the approximated output of the hidden layer
     */
    public INDArray propDown(INDArray h) {
        INDArray W = getParam(PretrainParamInitializer.WEIGHT_KEY).transpose();
        INDArray vBias = getParam(PretrainParamInitializer.VISIBLE_BIAS_KEY);

        INDArray vMean = h.mmul(W).addiRowVector(vBias);

        switch (layerConf().getVisibleUnit()) {
            case IDENTITY:
                return vMean;
            case BINARY:
                return sigmoid(vMean);
            case GAUSSIAN:
                Distribution dist = Nd4j.getDistributions().createNormal(vMean, 1);
                vMean = dist.sample(vMean.shape());
                return vMean;
            case LINEAR:
                return vMean;
            case SOFTMAX:
                return Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform("softmax", vMean));
            default:
                throw new IllegalStateException("Visible unit type should either be binary or gaussian " + layerId());
        }

    }

    /**
     * Reconstructs the visible INPUT.
     * A reconstruction is a propdown of the reconstructed hidden input.
     * @param  training true or false
     * @return the reconstruction of the visible input
     */
    @Override
    public Activations activate(boolean training) {
        applyPreprocessorIfNecessary(training);
        applyDropOutIfNecessary(training);
        //reconstructed: propUp ----> hidden propDown to transform
        INDArray propUp = propUp(input.get(0), training);
        return ActivationsFactory.getInstance().create(propUp);
    }

    @Override
    public Gradients backpropGradient(Gradients gradients) {
        INDArray input = this.input.get(0);
        INDArray epsilon = gradients.get(0);
        //If this layer is layer L, then epsilon is (w^(L+1)*(d^(L+1))^T) (or equivalent)
        INDArray z = preOutput(input, true);
        INDArray activationDerivative = propUpDerivative(z);
        INDArray delta = epsilon.muli(activationDerivative);

        if (this.input.getMask(0) != null) {
            delta.muliColumnVector(this.input.getMask(0));
        }

        Gradient ret = new DefaultGradient();

        INDArray weightGrad = gradientViews.get(DefaultParamInitializer.WEIGHT_KEY); //f order
        Nd4j.gemm(input, delta, weightGrad, true, false, 1.0, 0.0);
        INDArray biasGrad = gradientViews.get(DefaultParamInitializer.BIAS_KEY);
        delta.sum(biasGrad, 0); //biasGrad is initialized/zeroed first in sum op
        INDArray vBiasGradient = gradientViews.get(PretrainParamInitializer.VISIBLE_BIAS_KEY);

        ret.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, weightGrad);
        ret.gradientForVariable().put(DefaultParamInitializer.BIAS_KEY, biasGrad);
        ret.gradientForVariable().put(PretrainParamInitializer.VISIBLE_BIAS_KEY, vBiasGradient);

        INDArray epsilonNext = params.get(DefaultParamInitializer.WEIGHT_KEY).mmul(delta.transpose()).transpose();

        Gradients g = GradientsFactory.getInstance().create(epsilonNext, ret);
        return backpropPreprocessor(g);
    }

    @Override
    public boolean isPretrainLayer() {
        return true;
    }


}
