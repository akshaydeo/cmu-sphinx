/*
 * Copyright 2004 Carnegie Mellon University.  
 * Portions Copyright 2004 Sun Microsystems, Inc.  
 * Portions Copyright 2004 Mitsubishi Electronic Research Laboratories.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL 
 * WARRANTIES.
 *
 */

package edu.cmu.sphinx.util;

import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;



/*
 * A simple implementation of the batch manager suitable for single
 * threaded batch processing
 *
 */
public class SimpleBatchManager implements BatchManager {
    private String batchFile;
    private int skip;
    private int whichBatch;
    private int totalBatches;
    private int curItem;
    private List items;

    public SimpleBatchManager(String filename,
            int skip, int whichBatch, int totalBatches) {
        this.batchFile = filename;
        this.skip = skip;
        this.whichBatch = whichBatch;
        this.totalBatches = totalBatches;
    }

    /**
     * Starts processing the batch
     *
     */
    public void start() throws IOException {
        curItem = 0;
        items = getBatchItems(batchFile);
    }

    /**
     * Gets the next available batch item or null if no more are
     * available
     *
     * @return the next available batch item
     */
    public BatchItem getNextItem() throws IOException {
        if (curItem >= items.size()) {
            return null;
        } else {
            String line = (String) items.get(curItem++);
            return new BatchItem(BatchFile.getFilename(line),
                            BatchFile.getReference(line));
        }
    }

    /**
     * Stops processing the batch
     */
    public void stop() throws IOException {
    }

    /**
     * Returns the name of the file
     *
     * @return the filename
     */
    public String getFilename() {
        return batchFile;
    }

    /**
     * Gets the set of lines from the file
     *
     * @param file the name of the file 
     */
    private List getBatchItems(String file) throws IOException {
	List list = BatchFile.getLines(file, skip);

	if (totalBatches > 1) {
	    int linesPerBatch = list.size() / totalBatches;
	    if (linesPerBatch < 1) {
		linesPerBatch = 1;
	    }
	    if (whichBatch >= totalBatches) {
		whichBatch = totalBatches - 1;
	    }
	    int startLine = whichBatch * linesPerBatch;
	    // last batch needs to get all remaining lines
	    if (whichBatch == (totalBatches - 1)) {
		list = list.subList(startLine, list.size());
	    } else {
		list = list.subList(startLine, startLine +
			linesPerBatch);
	    }
	}
	return list;
    }
}

