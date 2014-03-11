/**
 * Copyright 1999-2012 Carnegie Mellon University. Portions Copyright 2002 Sun
 * Microsystems, Inc. Portions Copyright 2002 Mitsubishi Electric Research
 * Laboratories. All Rights Reserved. Use is subject to license terms. See the
 * file "license.terms" for information on usage and redistribution of this
 * file, and for a DISCLAIMER OF ALL WARRANTIES.
 */

package edu.cmu.sphinx.fst;

import static edu.cmu.sphinx.fst.Convert.importFst;
import static edu.cmu.sphinx.fst.operations.Connect.apply;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.io.File;

import org.testng.annotations.Test;

import edu.cmu.sphinx.Sphinx4TestCase;
import edu.cmu.sphinx.fst.semiring.TropicalSemiring;


/**
 * @author John Salatas <jsalatas@users.sourceforge.net>
 */
public class ConnectTest extends Sphinx4TestCase {

    @Test
    public void testConnect() {
        String path = "algorithms/connect/fstconnect.fst.ser";
        File parent = getResourceFile(path).getParentFile();

        path = new File(parent, "A").getPath();
        Fst fst = importFst(path, new TropicalSemiring());
        path = new File(parent, "fstconnect.fst.ser").getPath();
        Fst connectSaved = Fst.loadModel(path);
        
        apply(fst);
        assertThat(connectSaved, equalTo(fst));
    }

    public static void main(String[] args) {
        ConnectTest test = new ConnectTest();
        test.testConnect();
    }

}
