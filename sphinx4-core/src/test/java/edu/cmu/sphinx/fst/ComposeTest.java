/**
 * Copyright 1999-2012 Carnegie Mellon University. Portions Copyright 2002 Sun
 * Microsystems, Inc. Portions Copyright 2002 Mitsubishi Electric Research
 * Laboratories. All Rights Reserved. Use is subject to license terms. See the
 * file "license.terms" for information on usage and redistribution of this
 * file, and for a DISCLAIMER OF ALL WARRANTIES.
 */

package edu.cmu.sphinx.fst;

import static edu.cmu.sphinx.fst.Convert.importFst;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.io.File;

import org.testng.annotations.Test;

import edu.cmu.sphinx.Sphinx4TestCase;
import edu.cmu.sphinx.fst.operations.Compose;
import edu.cmu.sphinx.fst.semiring.TropicalSemiring;


/**
 * Compose Testing for Examples provided by M. Mohri,
 * "Weighted Automata Algorithms", Handbook of Weighted Automata,
 * Springer-Verlag, 2009, pp. 213–254.
 * 
 * @author John Salatas <jsalatas@users.sourceforge.net>
 */
public class ComposeTest extends Sphinx4TestCase {

    @Test
    public void testCompose() {
        String path = "algorithms/compose/fstcompose.fst.ser";
        File parent = getResourceFile(path).getParentFile();

        path = new File(parent, "A").getPath();
        Fst fstA = importFst(path, new TropicalSemiring());
        path = new File(parent, "B").getPath();
        Fst fstB = importFst(path, new TropicalSemiring());
        path = new File(parent, "fstcompose.fst.ser").getPath();
        Fst composed = Fst.loadModel(path);

        Fst fstComposed = Compose.get(fstA, fstB, new TropicalSemiring());
        assertThat(composed, equalTo(fstComposed));
    }
}
