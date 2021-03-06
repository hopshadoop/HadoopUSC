/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.tools.offlineEditsViewer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.hdfs.server.namenode.FSEditLogOpCodes;
import org.apache.hadoop.hdfs.tools.offlineEditsViewer.OfflineEditsViewer;
import org.apache.hadoop.hdfs.tools.offlineEditsViewer.TokenizerFactory;
import org.apache.hadoop.hdfs.tools.offlineEditsViewer.EditsVisitorFactory;
import org.apache.hadoop.hdfs.DFSTestUtil;

import org.apache.hadoop.hdfs.server.namenode.OfflineEditsViewerHelper;

public class TestOfflineEditsViewer {

  private static final Log LOG = LogFactory.getLog(TestOfflineEditsViewer.class);
  private static List<FSEditLogOpCodes> codes = new ArrayList<FSEditLogOpCodes>();
  static{
    codes.add(FSEditLogOpCodes.OP_INVALID);
    codes.add(FSEditLogOpCodes.OP_ADD);
    codes.add(FSEditLogOpCodes.OP_RENAME);
    codes.add(FSEditLogOpCodes.OP_DELETE);
    codes.add(FSEditLogOpCodes.OP_MKDIR);
    codes.add(FSEditLogOpCodes.OP_SET_REPLICATION);
    codes.add(FSEditLogOpCodes.OP_SET_PERMISSIONS);
    codes.add(FSEditLogOpCodes.OP_SET_OWNER);
    codes.add(FSEditLogOpCodes.OP_CLOSE);
    codes.add(FSEditLogOpCodes.OP_SET_GENSTAMP);
    codes.add(FSEditLogOpCodes.OP_TIMES);
    codes.add(FSEditLogOpCodes.OP_SET_QUOTA);
    codes.add(FSEditLogOpCodes.OP_CONCAT_DELETE);
  }

  private static String buildDir =
    System.getProperty("test.build.data", "build/test/data");

  private static String cacheDir =
    System.getProperty("test.cache.data", "build/test/cache");

  // to create edits and get edits filename
  private static final OfflineEditsViewerHelper nnHelper 
    = new OfflineEditsViewerHelper();

  
  @Test
  public void testEditsClean() throws IOException {
    testEdits(false);
  }
  
  @Test
  public void testEditsCrash() throws IOException {
    testEdits(true);
  }
  
  
  /**
   * Test the OfflineEditsViewer
   */
  private void testEdits(boolean simulateCrash) throws IOException {

    LOG.info("START - testing with generated edits, simulateCrash: " + simulateCrash);

    nnHelper.startCluster(buildDir + "/dfs/", simulateCrash);
    // force to rename edits.new into edits
    nnHelper.saveNS();

    // edits generated by nnHelper (MiniDFSCluster), should have all op codes
    // binary, XML, reparsed binary
    String edits          = nnHelper.generateEdits();
    
    //either clean or crash shutdown
    nnHelper.closeNS();
    
    String editsParsedXml = cacheDir + "/editsParsed.xml";
    String editsReparsed  = cacheDir + "/editsReparsed";

    // parse to XML then back to binary
    runOev(edits,          editsParsedXml, "xml");
    runOev(editsParsedXml, editsReparsed,  "binary");
    
    // judgment time
    assertTrue(
      "Edits " + edits + " should have all op codes",
      hasAllOpCodes(edits));
    assertTrue(
      "Generated edits and reparsed (bin to XML to bin) should be same",
      filesEqualIgnoreTrailingZeros(edits, editsReparsed));

    // removes edits so do this at the end
    nnHelper.shutdownCluster();

    LOG.info("END");
  }

  /**
   * Run OfflineEditsViewer
   *
   * @param inFilename input edits filename
   * @param outFilename oputput edits filename
   */
  private void runOev(String inFilename, String outFilename, String processor)
    throws IOException {

    LOG.info("Running oev [" + inFilename + "] [" + outFilename + "]");

    OfflineEditsViewer oev = new OfflineEditsViewer();
    oev.go( EditsVisitorFactory.getEditsVisitor(
      outFilename,
      processor,
      TokenizerFactory.getTokenizer(inFilename),
      false));
  }

  /**
   * Checks that the edits file has all opCodes
   *
   * @param filename edits file
   * @return true is edits (filename) has all opCodes
   */
  private boolean hasAllOpCodes(String inFilename) throws IOException {
    String outFilename = inFilename + ".stats";
    StatisticsEditsVisitor visitor =
      (StatisticsEditsVisitor)EditsVisitorFactory.getEditsVisitor(
        outFilename,
        "stats",
        TokenizerFactory.getTokenizer(inFilename),
        false);
    OfflineEditsViewer oev = new OfflineEditsViewer();
    oev.go(visitor);
    LOG.info("Statistics for " + inFilename + "\n" +
      visitor.getStatisticsString());
    
    boolean hasAllOpCodes = true;
    for(FSEditLogOpCodes opCode : codes) {
      Long count = visitor.getStatistics().get(opCode.getOpCode());
      if((count == null) || (count == 0)) {
        hasAllOpCodes = false;
        LOG.info("Opcode " + opCode + " not tested in " + inFilename);
      }
    }
    return hasAllOpCodes;
  }

  /**
   * Compare two files, ignore trailing zeros at the end,
   * for edits log the trailing zeros do not make any difference,
   * throw exception is the files are not same
   *
   * @param filenameSmall first file to compare (doesn't have to be smaller)
   * @param filenameLarge second file to compare (doesn't have to be larger)
   */
  private boolean filesEqualIgnoreTrailingZeros(String filenameSmall,
    String filenameLarge) throws IOException {

    ByteBuffer small = ByteBuffer.wrap(DFSTestUtil.loadFile(filenameSmall));
    ByteBuffer large = ByteBuffer.wrap(DFSTestUtil.loadFile(filenameLarge));

    // now correct if it's otherwise
    if(small.capacity() > large.capacity()) {
      ByteBuffer tmpByteBuffer = small;
      small = large;
      large = tmpByteBuffer;
      String tmpFilename = filenameSmall;
      filenameSmall = filenameLarge;
      filenameLarge = tmpFilename;
    }

    // compare from 0 to capacity of small
    // the rest of the large should be all zeros
    small.position(0);
    small.limit(small.capacity());
    large.position(0);
    large.limit(small.capacity());

    // compares position to limit
    if(!small.equals(large)) { return false; }

    // everything after limit should be 0xFF
    int i = large.limit();
    large.clear();
    for(; i < large.capacity(); i++) {
      if(large.get(i) != FSEditLogOpCodes.OP_INVALID.getOpCode()) {
        return false;
      }
    }

    return true;
  }
}
