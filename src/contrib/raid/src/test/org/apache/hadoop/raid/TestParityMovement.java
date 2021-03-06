package org.apache.hadoop.raid;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.DistributedRaidFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.TestRaidDfs;

import junit.framework.TestCase;

public class TestParityMovement extends TestCase {
  
  final static String TEST_DIR = new File(
      System.getProperty("test.build.data",
      "build/contrib/raid/test/data")).getAbsolutePath();

  final static String CONFIG_FILE = new File(TEST_DIR, 
      "test-raid.xml").getAbsolutePath();
  final static long RELOAD_INTERVAL = 1000;
  final static Log LOG = LogFactory.getLog(
            "org.apache.hadoop.raid.TestParityMovement");
  final static Random rand = new Random();
  final static int NUM_DATANODES = 3;
  
  Configuration conf;
  String namenode = null;
  String hftp = null;
  MiniDFSCluster dfs = null;
  FileSystem fileSys = null;
  Path root = null;
  Set<String> allExpectedMissingFiles = null;

  protected void mySetup(String erasureCode, int rsParityLength) 
      throws Exception {
    conf = new Configuration();
    if (System.getProperty("hadoop.log.dir") == null) {
      String base = new File(".").getAbsolutePath();
      System.setProperty("hadoop.log.dir", new Path(base).toString() 
                          + "/logs");
    }
    
    new File(TEST_DIR).mkdirs(); // Make sure data directory exists
    conf.set("raid.config.file", CONFIG_FILE);
    conf.setBoolean("raid.config.reload", true);
    conf.setLong("raid.config.reload.interval", RELOAD_INTERVAL);
    
    // the RaidNode does the raiding inline
    conf.set("raid.classname", "org.apache.hadoop.raid.LocalRaidNode");
    // use local block fixer
    conf.set("raid.blockfix.classname", 
        "org.apache.hadoop.raid.LocalBlockIntegrityMonitor");
    
    conf.set("raid.server.address", "localhost:0");
    conf.setInt("fs.trash.interval", 1440);
    Utils.loadTestCodecs(conf, 5, 1, 3, "/destraid", "/destraidrs");
    
    dfs = new MiniDFSCluster(conf, NUM_DATANODES, true, null);
    dfs.waitActive();
    fileSys = dfs.getFileSystem();
    namenode = fileSys.getUri().toString();
    hftp = "hftp://localhost.localdomain:" + dfs.getNameNodePort();
    
    FileSystem.setDefaultUri(conf, namenode);
    
    FileWriter fileWriter = new FileWriter(CONFIG_FILE);
    fileWriter.write("<?xml version=\"1.0\"?>\n");
    String str = "<configuration> " +
        "<policy name = \"RaidTest1\"> " +
          "<srcPath prefix=\"/user/dhruba/raidtest\"/> " +
          "<codecId>xor</codecId> " +
          "<destPath> /destraid</destPath> " +
          "<property> " +
            "<name>targetReplication</name> " +
            "<value>1</value> " + 
            "<description>after RAIDing, " +
            "decrease the replication factor of a file to this value." +
            "</description> " + 
          "</property> " +
          "<property> " +
            "<name>metaReplication</name> " +
            "<value>1</value> " + 
            "<description> replication factor of parity file" +
            "</description> " + 
          "</property> " +
          "<property> " +
            "<name>modTimePeriod</name> " +
            "<value>2000</value> " + 
            "<description> time (milliseconds) " +
              "after a file is modified to make it " +
              "a candidate for RAIDing " +
            "</description> " + 
          "</property> " +
        "</policy>" +
        "</configuration>";
    fileWriter.write(str);
    fileWriter.close();
  }

  private DistributedRaidFileSystem getRaidFS() throws IOException {
    DistributedFileSystem dfs = (DistributedFileSystem)fileSys;
    Configuration clientConf = new Configuration(conf);
    clientConf.set("fs.hdfs.impl", 
             "org.apache.hadoop.hdfs.DistributedRaidFileSystem");
    clientConf.set("fs.raid.underlyingfs.impl", 
             "org.apache.hadoop.hdfs.DistributedFileSystem");
    clientConf.setBoolean("fs.hdfs.impl.disable.cache", true);
    URI dfsUri = dfs.getUri();
    return (DistributedRaidFileSystem)FileSystem.get(dfsUri, clientConf);
  }

  private void doRaid(Path srcPath, Codec codec) throws IOException {
    RaidNode.doRaid(conf, fileSys.getFileStatus(srcPath),
              new Path("/destraid"), codec, 
              new RaidNode.Statistics(), 
                RaidUtils.NULL_PROGRESSABLE,
                false, 1, 1);
  }
  
  public void testRename() throws Exception {
    try {
      mySetup("xor", 1);
      
      Path srcPath = new Path("/user/dhruba/raidtest/rename/f1");
      Path destPath = new Path("/user/dhruba/raidtest/rename/f2");
      
      Path srcPath2 = new Path("/user/dhruba/raidtest/rename/f3");
      Path destDirPath = new Path("/user/dhruba/raidtest/rename2");
      Path destPath2 = new Path("/user/dhruba/raidtest/rename2/f3");
      
      TestRaidDfs.createTestFilePartialLastBlock(fileSys, srcPath, 
                            1, 8, 8192L);
      TestRaidDfs.createTestFilePartialLastBlock(fileSys, srcPath2, 
                            1, 8, 8192L);
      DistributedRaidFileSystem raidFs = getRaidFS();
      assertTrue(raidFs.exists(srcPath));
      assertFalse(raidFs.exists(destPath));
      // generate the parity files.
      doRaid(srcPath, Codec.getCodec("xor"));
      doRaid(srcPath2, Codec.getCodec("xor"));
      ParityFilePair parity = ParityFilePair.getParityFile(
          Codec.getCodec("xor"), 
          srcPath, 
          conf);
      Path srcParityPath = parity.getPath();
      assertTrue(raidFs.exists(srcParityPath));
      parity = ParityFilePair.getParityFile(Codec.getCodec("xor"),
          destPath,
          conf);
      assertNull(parity);
      // do the rename file
      assertTrue(raidFs.rename(srcPath, destPath));
      // verify the results.
      assertFalse(raidFs.exists(srcPath));
      assertTrue(raidFs.exists(destPath));
      assertFalse(raidFs.exists(srcParityPath));
      parity = ParityFilePair.getParityFile(Codec.getCodec("xor"),
          destPath,
          conf);
      assertTrue(raidFs.exists(parity.getPath()));
      
      // rename the dir
      assertFalse(raidFs.exists(destDirPath));
      assertTrue(raidFs.rename(srcPath2.getParent(), destDirPath));
      // verify the results.
      assertFalse(raidFs.exists(srcPath2.getParent()));
      assertTrue(raidFs.exists(destDirPath));
      
      parity = ParityFilePair.getParityFile(Codec.getCodec("xor"),
          destPath2,
          conf);
      assertTrue(raidFs.exists(parity.getPath()));
    } finally {
      if (null != dfs) {
        dfs.shutdown();
      }
    }
  }

  public void testDeleteAndUndelete() throws Exception {
    try {
      mySetup("xor", 1);

      Path srcPath = new Path("/user/dhruba/raidtest/rename/f1");
      Path srcPath2 = new Path("/user/dhruba/raidtest/rename/f2");

      TestRaidDfs.createTestFilePartialLastBlock(fileSys, srcPath, 
          1, 8, 8192L);
      TestRaidDfs.createTestFilePartialLastBlock(fileSys, srcPath2, 
          1, 8, 8192L);
      DistributedRaidFileSystem raidFs = getRaidFS();
      assertTrue(raidFs.exists(srcPath));
      assertTrue(raidFs.exists(srcPath2));

      // generate the parity files.
      doRaid(srcPath, Codec.getCodec("xor"));
      doRaid(srcPath2, Codec.getCodec("xor"));
      ParityFilePair parity = ParityFilePair.getParityFile(
          Codec.getCodec("xor"),
          srcPath, 
          conf);
      Path srcParityPath = parity.getPath();
      ParityFilePair parity2 = ParityFilePair.getParityFile(
          Codec.getCodec("xor"),
          srcPath2, 
          conf);
      Path srcParityPath2 = parity2.getPath();
      assertTrue(raidFs.exists(srcParityPath));
      assertTrue(raidFs.exists(srcParityPath2));

      // do the delete file
      assertTrue(raidFs.delete(srcPath));

      // verify the results.
      assertFalse(raidFs.exists(srcPath));
      assertFalse(raidFs.exists(srcParityPath));
      assertTrue(raidFs.exists(srcParityPath2));

      // do the undelete using non-exist userName
      String nonExistedUser = UUID.randomUUID().toString();
      assertFalse(raidFs.undelete(srcPath, nonExistedUser));

      // verify the results
      assertFalse(raidFs.exists(srcPath));
      assertFalse(raidFs.exists(srcParityPath));
      assertTrue(raidFs.exists(srcParityPath2));

      // do the undelete file using current userName
      assertTrue(raidFs.undelete(srcPath, null));
      //verify the results.
      assertTrue(raidFs.exists(srcPath));
      assertTrue(raidFs.exists(srcParityPath));
      assertTrue(raidFs.exists(srcParityPath2));

      // delete the dir
      assertTrue(raidFs.delete(srcPath2.getParent()));
      // verify the results.
      assertFalse(raidFs.exists(srcPath2.getParent()));
      assertFalse(raidFs.exists(srcParityPath));
      assertFalse(raidFs.exists(srcParityPath2));
      assertFalse(raidFs.exists(srcParityPath.getParent()));

      // undelete the dir
      assertTrue(raidFs.undelete(srcPath2.getParent(), null));
      // verify the results.
      assertTrue(raidFs.exists(srcPath));
      assertTrue(raidFs.exists(srcPath2));
      assertTrue(raidFs.exists(srcParityPath));
      assertTrue(raidFs.exists(srcParityPath2));
    } finally {
      if (null != dfs) {
        dfs.shutdown();
      }
    }
  }
}
