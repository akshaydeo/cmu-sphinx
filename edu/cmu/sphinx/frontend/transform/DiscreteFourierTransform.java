/*
 * Copyright 1999-2004 Carnegie Mellon University.  
 * Portions Copyright 2002-2004 Sun Microsystems, Inc.  
 * Portions Copyright 2002-2004 Mitsubishi Electric Research Laboratories.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL 
 * WARRANTIES.
 *
 */

package edu.cmu.sphinx.frontend.transform;

import edu.cmu.sphinx.frontend.BaseDataProcessor;
import edu.cmu.sphinx.frontend.Data;
import edu.cmu.sphinx.frontend.DataProcessingException;
import edu.cmu.sphinx.frontend.DoubleData;
import edu.cmu.sphinx.util.Complex;
import edu.cmu.sphinx.util.props.PropertyException;
import edu.cmu.sphinx.util.props.PropertySheet;
import edu.cmu.sphinx.util.props.PropertyType;
import edu.cmu.sphinx.util.props.Registry;

import java.util.logging.Logger;


/**
 * Computes the Discrete Fourier Transform (FT) of an input sequence,
 * using Fast Fourier Transform (FFT). Fourier Transform is the
 * process of analyzing a signal into its frequency components. In
 * speech, rather than analyzing the signal over its entrie duration,
 * we analyze one <b>window</b> of audio data. This window is the
 * product of applying a sliding Hamming window to the
 * signal. Moreover, since the amplitude is a lot more important than
 * the phase for speech recognition, this class returns the power
 * spectrum of that window of data instead of the complex spectrum.
 * Each value in the returned spectrum represents the strength of that
 * particular frequency for that window of data.
 * <p> 
 * By default, the number of FFT points is the closest power pf 2
 * that is equal to or larger than the number of samples in the incoming
 * window of data. The FFT points can also be set by the user with the
 * property defined by {@link #PROP_NUMBER_FFT_POINTS}.
 * The length of the returned power
 * spectrum is the number of FFT points, divided by 2, plus 1. Since
 * the input signal is real, the FFT is symmetric, and the information
 * contained in the whole vector is already present in its first half.
 * <p>
 * Note that each call to {@link #getData() getData} only returns
 * the spectrum of one window of data. To display the spectrogram of
 * the entire original audio, one has to collect all the spectra from
 * all the windows generated from the original data. A spectrogram is
 * a two dimensional representation of three dimensional
 * information. The horizontal axis represents time. The vertical axis
 * represents the frequency. If we slice the spectrogram at a given
 * time, we get the spectrum computed as the short term Fourier
 * transform of the signal windowed around that time stamp. The
 * intensity of the spectrum for each time frame is given by the color
 * in the graph, or by the darkness in a gray scale plot. The
 * spectrogram can be thought of as a view from the top of a surface
 * generated by concatenating the spectral vectors obtained from the
 * windowed signal.  
 * <p>
 * For example, Figure 1 below shows the audio signal of the
 * utterance "one three nine oh", and Figure 2 shows its spectrogram,
 * produced by putting together all the spectra returned by this FFT.
 * Frequency is on the vertical axis, and time is on the horizontal
 * axis. The darkness of the shade represents the strength of that
 * frequency at that point in time: <p> <br><img
 * src="doc-files/139o.jpg"> <br><b>Figure 1: The audio signal of the
 * utterance "one three nine oh".</b> <p> <br><img
 * src="doc-files/139ospectrum.jpg"> <br><b>Figure 2: The spectrogram
 * of the utterance "one three nine oh" in Figure 1.</b>
 */
public class DiscreteFourierTransform extends BaseDataProcessor {
    /**
     * The name of the SphinxProperty for the number of points
     * in the Fourier Transform.
     */
    public static final String PROP_NUMBER_FFT_POINTS
        =  "numberFftPoints";

    private boolean isNumberFftPointsSet;
    private int numberFftPoints;
    private int logBase2NumberFftPoints;
    private int numberDataPoints;

    private Complex[] weightFft;
    private Complex[] inputFrame;
    private Complex[] from;
    private Complex[] to;

    private Complex weightFftTimesFrom2;
    private Complex tempComplex;

    private Logger logger;

    
    /*
     * (non-Javadoc)
     * 
     * @see edu.cmu.sphinx.util.props.Configurable#register(java.lang.String,
     *      edu.cmu.sphinx.util.props.Registry)
     */
    public void register(String name, Registry registry)
            throws PropertyException {
        super.register(name, registry);
        registry.register(PROP_NUMBER_FFT_POINTS, PropertyType.INT);
    }

    /*
     * (non-Javadoc)
     * 
     * @see edu.cmu.sphinx.util.props.Configurable#newProperties(edu.cmu.sphinx.util.props.PropertySheet)
     */
    public void newProperties(PropertySheet ps) throws PropertyException {
        super.newProperties(ps);
        logger = ps.getLogger();
        numberFftPoints = ps.getInt(PROP_NUMBER_FFT_POINTS, -1);
        isNumberFftPointsSet = (numberFftPoints != -1);
    }

    /* (non-Javadoc)
     * @see edu.cmu.sphinx.frontend.DataProcessor#initialize(edu.cmu.sphinx.frontend.CommonConfig)
     */
    public void initialize() {
        super.initialize();
        if (isNumberFftPointsSet) {
            initializeFFT();
        }
    }

    /**
     * Initialize all the data structures necessary for computing FFT.
     */
    private void initializeFFT() {
	/**
	 * Number of points in the FFT. By default, the value is 512,
	 * which means that we compute 512 values around a circle in the
	 * complex plane. Complex conjugate pairs will yield the same
	 * power, therefore the power produced by indices 256 through
	 * 511 are symmetrical with the ones between 1 and 254. Therefore,
	 * we need only return values between 0 and 255.
	 */
	computeLogBase2(numberFftPoints);
	createWeightFft(numberFftPoints, false);
        initComplexArrays();
        weightFftTimesFrom2 = new Complex();
	tempComplex = new Complex();
    }


    /**
     * Initialize all the Complex arrays that will be necessary for FFT.
     */
    private void initComplexArrays() {

        inputFrame = new Complex[numberFftPoints];
    	from = new Complex[numberFftPoints];
	to = new Complex[numberFftPoints];
        
        for (int i = 0; i < numberFftPoints; i++) {
            inputFrame[i] = new Complex();
            from[i] = new Complex();
            to[i] = new Complex();
        }
    }


    /**
     * Process data, creating the power spectrum from an input frame.
     *
     * @param input the input frame
     *
     * @return a DoubleData that is the power spectrum of the input frame
     *
     * @throws java.lang.IllegalArgumentException
     */
    private DoubleData process(DoubleData input)
	throws IllegalArgumentException {

        /**
	 * Create complex input sequence equivalent to the real
	 * input sequence.
	 * If the number of points is less than the window size,
	 * we incur in aliasing. If it's greater, we pad the input
	 * sequence with zeros.
	 */
	double[] in = input.getValues();

        if (numberFftPoints < in.length) {
            int i = 0;
	    for (; i < numberFftPoints; i++) {
		inputFrame[i].set(in[i], 0.0f);
	    }
	    for (; i < in.length; i++) {
                tempComplex.set(in[i], 0.0f);
		inputFrame[i % numberFftPoints].addComplex
		    (inputFrame[i % numberFftPoints], tempComplex);
	    }
	} else {
            int i = 0;
	    for (; i < in.length; i++) {
		inputFrame[i].set(in[i], 0.0f);
	    }
	    for (; i < numberFftPoints; i++) {
		inputFrame[i].reset();
	    }
	}

	/**
	 * Create output sequence.
	 */
	double[] outputSpectrum = new double[(numberFftPoints >> 1) + 1];

	/**
	 * Start Fast Fourier Transform recursion
	 */
	recurseFft(inputFrame, outputSpectrum, numberFftPoints, false);

	/**
	 * Return the power spectrum
	 */
	DoubleData output = new DoubleData
            (outputSpectrum,  input.getSampleRate(), 
                    input.getCollectTime(),
	     input.getFirstSampleNumber());

        return output;
    }


    /**
     * Make sure the number of points in the FFT is a power
     * of 2 by computing its log base 2 and checking for
     * remainders.
     *
     * @param numberFftPoints number of points in the FFT
     *
     * @throws java.lang.IllegalArgumentException
     */
    private void computeLogBase2(int numberFftPoints)
	throws IllegalArgumentException {
	this.logBase2NumberFftPoints = 0;
	for (int k = numberFftPoints; k > 1;
             k >>= 1, this.logBase2NumberFftPoints++) {
	    if (((k % 2) != 0) || (numberFftPoints < 0)) {
		throw new IllegalArgumentException("Not a power of 2: "
						   + numberFftPoints);
	    }
	}
    }


    /**
     * Initializes the <b>weightFft[]</b> vector.
     * <p><b>weightFft[k] = w ^ k</b></p>
     * where:
     * <p><b>w = exp(-2 * PI * i / N)</b></p>
     * <p><b>i</b> is a complex number such that <b>i * i = -1</b>
     * and <b>N</b> is the number of points in the FFT.
     * Since <b>w</b> is complex, this is the same as</p>
     * <p><b>Re(weightFft[k]) = cos ( -2 * PI * k / N)</b></p>
     * <p><b>Im(weightFft[k]) = sin ( -2 * PI * k / N)</b></p>
     *
     * @param numberFftPoints number of points in the FFT
     * @param invert whether it's direct (false) or inverse (true) FFT
     *
     */
    private void createWeightFft(int numberFftPoints, boolean invert) {
	/**
	 * weightFFT will have numberFftPoints/2 complex elements.
	 */
        weightFft = new Complex[numberFftPoints >> 1];

	/**
	 * For the inverse FFT,
	 * w = 2 * PI / numberFftPoints;
	 */
	double w = -2 * Math.PI / numberFftPoints;
        if (invert) {
            w = -w;
        }
	
	for (int k = 0; k < (numberFftPoints >> 1); k++) {
            weightFft[k] = new Complex(Math.cos (w * k), Math.sin (w * k));
	}
    }


    /**
     * Reads the next DoubleData object, which is a data frame from which
     * we'll compute the power spectrum. Signal objects just pass through
     * unmodified.
     *
     * @return the next available power spectrum DoubleData object,
     *         or null if no Spectrum object is available
     *
     * @throws DataProcessingException if there is a processing error
     */
    public Data getData() throws DataProcessingException {

	Data input = getPredecessor().getData();
        
        getTimer().start();

        if ((input != null) && (input instanceof DoubleData)) {
            DoubleData data = (DoubleData) input;
            if (!isNumberFftPointsSet) {
                /*
                 * If numberFftPoints is not set by the user,
                 * figure out the numberFftPoints and initialize the
                 * data structures appropriately.
                 */
                if (numberDataPoints != data.getValues().length) {
                    numberDataPoints = data.getValues().length;
                    numberFftPoints = getNumberFftPoints(numberDataPoints);
                    initializeFFT();
                }
            } else {
                /*
                 * Warn if the user-set numberFftPoints is not ideal.
                 */
                if (numberDataPoints != data.getValues().length) {
                    numberDataPoints = data.getValues().length;
                    int idealFftPoints = getNumberFftPoints(numberDataPoints);
                    if (idealFftPoints != numberFftPoints) {
                        logger.warning("User set numberFftPoints (" + 
                                       numberFftPoints + ") is not ideal (" + 
                                       idealFftPoints + ")");
                    }
                }
            }
	    input = process(data);
	}
        
        // At this point - or in the call immediatelly preceding
        // this -, we should have created a cepstrum frame with
        // whatever data came last, even if we had less than
        // window size of data.
        
        getTimer().stop();
	
	return input;
    }


    /**
     * Returns the ideal number of FFT points given the number of samples.
     * The ideal number of FFT points is the closest power of 2 that is 
     * equal to or larger than the number of samples in the incoming window.
     *
     * @param numberSamples the number of samples in the incoming window
     *
     * @return the closest power of 2 that is equal to or larger than
     *         the number of samples in the incoming window
     */
    private final static int getNumberFftPoints(int numberSamples) {
        int fftPoints = 1;
        
        while (fftPoints < numberSamples) {
            fftPoints *= 2;
            if (fftPoints < 1 || fftPoints > Integer.MAX_VALUE) {
                throw new Error("Invalid # of FFT points: " + fftPoints);
            }
        }
        return fftPoints;
    }


    /**
     * Establish the recursion. The FFT computation will be 
     * computed by as a recursion. Each stage in the butterfly
     * will be fully computed during recursion. In fact, we use
     * the mechanism of recursion only because it's the simplest 
     * way of switching the "input" and "output" vectors. The 
     * output of a stage is the input to the next stage. The 
     * butterfly computes elements in place, but we still need to
     * switch the vectors. We could copy it (not very efficient...)
     * or, in C, switch the pointers. We can avoid the pointers by
     * using recursion.
     *
     * @param input input sequence
     * @param output output sequence
     * @param numberFftPoints number of points in the FFT
     * @param invert whether it's direct (false) or inverse (true) FFT
     *
     */
    private void recurseFft(Complex[] input,
                            double[] output,
                            int numberFftPoints,
                            boolean invert) {
        
	double divisor;
        
	/**
	 * The direct and inverse FFT are essentially the same
	 * algorithm, except for two difference: a scaling factor of
	 * "numberFftPoints" and the signal of the exponent in the
	 * weightFft vectors, defined in the method
	 * <code>createWeightFft</code>.
	 */

 	if (!invert) {
	    divisor = 1.0;
	} else {
	    divisor = (double) numberFftPoints;
	}

	/**
	 * Initialize the "from" and "to" variables.
	 */
	for (int i = 0; i < numberFftPoints; i++){
	    to[i].reset();
	    from[i].scaleComplex(input[i], divisor);
	}

	/**
	 * Repeat the recursion log2(numberFftPoints) times,
	 * i.e., we have log2(numberFftPoints) butterfly stages.
	 */
	butterflyStage(from, to, numberFftPoints, numberFftPoints >> 1);

	/**
	 * Compute energy ("float") for each frequency point
	 * from the fft ("complex")
	 */
	if ((this.logBase2NumberFftPoints & 1) == 0) {
	    for (int i = 0; i <= (numberFftPoints >> 1); i++){
		output[i] = from[i].squaredMagnitudeComplex(); 
            }
	} else {
	    for (int i = 0; i <= (numberFftPoints >> 1); i++){
		output[i] = to[i].squaredMagnitudeComplex();
            }
	}
	return;
    }


    /**
     * Compute one stage in the FFT butterfly. The name "butterfly"
     * appears because this method computes elements in pairs, and
     * a flowgraph of the computation (output "0" comes from input "0"
     * and "1" and output "1" comes from input "0" and "1") resembles a
     * butterfly.
     *
     * We repeat <code>butterflyStage</code> for
     * <b>log_2(numberFftPoints)</b> stages, by calling the recursion
     * with the argument <code>currentDistance</code> divided by 2 at
     * each call, and checking if it's still > 0.
     *
     * @param from the input sequence at each stage
     * @param to the output sequence
     * @param numberFftPoints the total number of points
     * @param currentDistance the "distance" between elements in the
     *     butterfly
     */
    private void butterflyStage(Complex[] from, 
				Complex[] to, 
				int numberFftPoints,
				int currentDistance)
    {
	int ndx1From;
	int ndx2From;
	int ndx1To;
	int ndx2To;
	int ndxWeightFft;

	if (currentDistance > 0) {

            int twiceCurrentDistance = 2 * currentDistance;

	    for (int s = 0; s < currentDistance; s++) {
		ndx1From = s;
		ndx2From = s + currentDistance;
		ndx1To = s;
		ndx2To = s + (numberFftPoints >> 1);
		ndxWeightFft = 0;
		while (ndxWeightFft < (numberFftPoints >> 1)) {
		    /**
		     * <b>weightFftTimesFrom2 = weightFft[k]   </b>
		     * <b>                      *from[ndx2From]</b>
		     */
		    weightFftTimesFrom2.multiplyComplex
			(weightFft[ndxWeightFft], from[ndx2From]);
		    /**
		     * <b>to[ndx1To] = from[ndx1From]       </b>
		     * <b>             + weightFftTimesFrom2</b>
		     */
		    to[ndx1To].addComplex
			(from[ndx1From], weightFftTimesFrom2);
		    /**
		     * <b>to[ndx2To] = from[ndx1From]       </b>
		     * <b>             - weightFftTimesFrom2</b>
		     */
		    to[ndx2To].subtractComplex
			(from[ndx1From], weightFftTimesFrom2);
		    ndx1From += twiceCurrentDistance;
		    ndx2From += twiceCurrentDistance;
		    ndx1To += currentDistance;
		    ndx2To += currentDistance;
		    ndxWeightFft += currentDistance;
		}
	    }

	    /**
	     * This call'd better be the last call in this block, so when
	     * it returns we go straight into the return line below.
	     *
	     * We switch the <i>to</i> and <i>from</i> variables,
	     * the total number of points remains the same, and
	     * the <i>currentDistance</i> is divided by 2.
	     */
	    butterflyStage(to, from, numberFftPoints, (currentDistance >> 1));
	}
	return;
    }
}
