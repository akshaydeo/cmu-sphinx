/**
 * [[[copyright]]]
 */

package tests.frontend;

import edu.cmu.sphinx.frontend.Cepstrum;
import edu.cmu.sphinx.frontend.CepstrumSource;
import edu.cmu.sphinx.frontend.DataProcessor;
import edu.cmu.sphinx.frontend.Signal;

import java.io.IOException;
import java.util.*;

/**
 * Monitors the Cepstra that passes through this Monitor.
 * The exact monitoring work to be done should be specified
 * in the abstract method "cepstrumMonitored()".
 */
public abstract class CepstrumMonitor 
extends DataProcessor implements CepstrumSource {

    private CepstrumSource predecessor;


    /**
     * Constructs a CepstraMonitor with the given name, context,
     * and predecessor.
     */
    public CepstrumMonitor(String name, String context, 
                           CepstrumSource predecessor) {
        super(name, context);
        this.predecessor = predecessor;
    }

    /**
     * Override this method to specify what should be done when
     * a Cepstrum is monitored.
     */
    public abstract void cepstrumMonitored(Cepstrum ceptrum);

    /**
     * Returns the next available Cepstrum.
     */
    public Cepstrum getCepstrum() throws IOException {
        
        Cepstrum cepstrum = predecessor.getCepstrum();
        if (cepstrum != null) {
            Cepstrum copy = cepstrum;

            if (cepstrum.hasContent()) {
                // Need to replicate the Cepstrum since the original
                // Cepstrum will be normalized in a later step.
                // Here, though, we only need the first element (energy).
                float[] data = new float[1];
                data[0] = cepstrum.getCepstrumData()[0];
                copy = new Cepstrum(data, cepstrum.getUtterance());
            }
            cepstrumMonitored(copy);
        }

        return cepstrum;
    }
}
