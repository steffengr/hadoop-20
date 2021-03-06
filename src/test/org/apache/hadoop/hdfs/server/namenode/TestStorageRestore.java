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
package org.apache.hadoop.hdfs.server.namenode;


import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Random;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.hdfs.server.namenode.FSImage.NameNodeDirType;
import org.apache.hadoop.hdfs.server.namenode.FSImage.NameNodeFile;


/**
 * Startup and checkpoint tests
 * 
 */
public class TestStorageRestore extends TestCase {
  public static final String NAME_NODE_HOST = "localhost:";
  public static final String NAME_NODE_HTTP_HOST = "0.0.0.0:";
  private static final Log LOG =
    LogFactory.getLog(TestStorageRestore.class.getName());
  private Configuration config;
  private File hdfsDir=null;
  static final long seed = 0xAAAAEEFL;
  static final int blockSize = 4096;
  static final int fileSize = 8192;
  private File path1, path2, path3;
  private MiniDFSCluster cluster;

  private void writeFile(FileSystem fileSys, Path name, int repl)
  throws IOException {
    FSDataOutputStream stm = fileSys.create(name, true,
        fileSys.getConf().getInt("io.file.buffer.size", 4096),
        (short)repl, (long)blockSize);
    byte[] buffer = new byte[fileSize];
    Random rand = new Random(seed);
    rand.nextBytes(buffer);
    stm.write(buffer);
    stm.close();
  }
  
 
  protected void setUp() throws Exception {
    config = new Configuration();
    String baseDir = System.getProperty("test.build.data", "/tmp");
    
    hdfsDir = new File(baseDir, "dfs");
    if ( hdfsDir.exists() && !FileUtil.fullyDelete(hdfsDir) ) {
      throw new IOException("Could not delete hdfs directory '" + hdfsDir + "'");
    }
    
    hdfsDir.mkdir();
    path1 = new File(hdfsDir, "name1");
    path2 = new File(hdfsDir, "name2");
    path3 = new File(hdfsDir, "name3");
    
    path1.mkdir(); path2.mkdir(); path3.mkdir();
    if(!path2.exists() ||  !path3.exists() || !path1.exists()) {
      throw new IOException("Couldn't create dfs.name dirs");
    }
    
    String dfs_name_dir = new String(path1.getPath() + "," + path2.getPath());
    System.out.println("configuring hdfsdir is " + hdfsDir.getAbsolutePath() + 
        "; dfs_name_dir = "+ dfs_name_dir + ";dfs_name_edits_dir(only)=" + path3.getPath());
    
    config.set("dfs.name.dir", dfs_name_dir);
    config.set("dfs.name.edits.dir", dfs_name_dir + "," + path3.getPath());

    config.set("fs.checkpoint.dir",new File(hdfsDir, "secondary").getPath());
 
    FileSystem.setDefaultUri(config, "hdfs://"+NAME_NODE_HOST + "0");
    
    config.set("dfs.secondary.http.address", "0.0.0.0:0");
    
    // set the restore feature on
    config.setBoolean("dfs.name.dir.restore", true);
  }

  /**
   * clean up
   */
  public void tearDown() throws Exception {
    if (hdfsDir.exists() && !FileUtil.fullyDelete(hdfsDir) ) {
      throw new IOException("Could not delete hdfs directory in tearDown '" + hdfsDir + "'");
    }
  }
  
  /**
   * invalidate storage by removing current directories
   */
  public void invalidateStorage(FSImage fi) throws IOException {
    fi.getEditLog().processIOError(2); //name3
    fi.getEditLog().processIOError(0); //name1
  }
  
  /**
   * test
   */
  public void printStorages(FSImage fs) {
    LOG.info("current storages and corresoponding sizes:");
    for(Iterator<StorageDirectory> it = fs.dirIterator(); it.hasNext(); ) {
      StorageDirectory sd = it.next();
      
      if(sd.getStorageDirType().isOfType(NameNodeDirType.IMAGE)) {
        File imf = FSImage.getImageFile(sd, NameNodeFile.IMAGE);
        LOG.info("  image file " + imf.getAbsolutePath() + "; len = " + imf.length());  
      }
      if(sd.getStorageDirType().isOfType(NameNodeDirType.EDITS)) {
        File edf = FSImage.getImageFile(sd, NameNodeFile.EDITS);
        LOG.info("  edits file " + edf.getAbsolutePath() + "; len = " + edf.length()); 
      }
    }
  }
  
  /**
   *  check if files exist/not exist
   */
  public void checkFiles(boolean valid) {
    //look at the valid storage
    File fsImg1 = new File(path1, Storage.STORAGE_DIR_CURRENT + "/" + NameNodeFile.IMAGE.getName());
    File fsImg2 = new File(path2, Storage.STORAGE_DIR_CURRENT + "/" + NameNodeFile.IMAGE.getName());
    File fsImg3 = new File(path3, Storage.STORAGE_DIR_CURRENT + "/" + NameNodeFile.IMAGE.getName());

    File fsEdits1 = new File(path1, Storage.STORAGE_DIR_CURRENT + "/" + NameNodeFile.EDITS.getName());
    File fsEdits2 = new File(path2, Storage.STORAGE_DIR_CURRENT + "/" + NameNodeFile.EDITS.getName());
    File fsEdits3 = new File(path3, Storage.STORAGE_DIR_CURRENT + "/" + NameNodeFile.EDITS.getName());

    this.printStorages(cluster.getNameNode().getFSImage());
    
    LOG.info("++++ image files = "+fsImg1.getAbsolutePath() + "," + fsImg2.getAbsolutePath() + ","+ fsImg3.getAbsolutePath());
    LOG.info("++++ edits files = "+fsEdits1.getAbsolutePath() + "," + fsEdits2.getAbsolutePath() + ","+ fsEdits3.getAbsolutePath());
    LOG.info("checkFiles compares lengths: img1=" + fsImg1.length()  + ",img2=" + fsImg2.length()  + ",img3=" + fsImg3.length());
    LOG.info("checkFiles compares lengths: edits1=" + fsEdits1.length()  + ",edits2=" + fsEdits2.length()  + ",edits3=" + fsEdits3.length());
    
    if(valid) {
      assertTrue(fsImg1.exists());
      assertTrue(fsImg2.exists());
      assertFalse(fsImg3.exists());
      assertTrue(fsEdits1.exists());
      assertTrue(fsEdits2.exists());
      assertTrue(fsEdits3.exists());
      
     // should be the same
      assertTrue(fsImg1.length() == fsImg2.length());
      assertTrue(fsEdits1.length() == fsEdits2.length());
      assertTrue(fsEdits1.length() == fsEdits3.length());
    } else {
      // should be different
      assertTrue(fsEdits2.length() != fsEdits1.length());
      assertTrue(fsEdits2.length() != fsEdits3.length());
    }
  }
  
  /**
   * test 
   * 1. create DFS cluster with 3 storage directories - 2 EDITS_IMAGE, 1 EDITS
   * 2. create a cluster and write a file
   * 3. corrupt/disable one storage (or two) by removing
   * 4. run doCheckpoint - it will fail on removed dirs (which
   * will invalidate the storages)
   * 5. write another file
   * 6. check that edits and fsimage differ 
   * 7. run doCheckpoint
   * 8. verify that all the image and edits files are the same.
   */
  public void testStorageRestore() throws Exception {
    int numDatanodes = 2;
    //Collection<String> dirs = config.getStringCollection("dfs.name.dir");
    cluster = new MiniDFSCluster(0, config, numDatanodes, true, false, true,  null, null, null, null);
    cluster.waitActive();
    
    SecondaryNameNode secondary = new SecondaryNameNode(config);
    System.out.println("****testStorageRestore: Cluster and SNN started");
    printStorages(cluster.getNameNode().getFSImage());
    
    FileSystem fs = cluster.getFileSystem();
    Path path = new Path("/", "test");
    writeFile(fs, path, 2);
    System.out.println("****testStorageRestore: going to do checkpoint");
    secondary.doCheckpoint();
    checkFiles(true);
    
    System.out.println("****testStorageRestore: file test written, invalidating storage...");
  
    invalidateStorage(cluster.getNameNode().getFSImage());
    //secondary.doCheckpoint(); // this will cause storages to be removed.
    printStorages(cluster.getNameNode().getFSImage());
    System.out.println("****testStorageRestore: storage invalidated + doCheckpoint");

    path = new Path("/", "test1");
    writeFile(fs, path, 2);
    System.out.println("****testStorageRestore: file test1 written");
    
    checkFiles(false); // SHOULD BE FALSE
    
    System.out.println("****testStorageRestore: checkfiles(false) run");
    
    secondary.doCheckpoint();  ///should enable storage..
    System.out.println("****testStorageRestore: second Checkpoint done");
    
    checkFiles(true);

    System.out.println("****testStorageRestore: checkFiles(true) run");
    
    secondary.shutdown();
    cluster.shutdown();
  }
}
