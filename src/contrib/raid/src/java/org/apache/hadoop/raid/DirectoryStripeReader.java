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

package org.apache.hadoop.raid;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;

/*
 * DirectoryStripeReader is used in directory-raid encoder and
 * decoder to return a bunch of input streams in a stripe.
 * When it's initiated, it lists all blocks under the source directory
 * and puts them in a list stripeBlocks. We use stripeBlocks to
 * locate the file and block index for specific block.
 * For encoder case, we use hasNext() and getNextStripeInputs()
 * to iterate each stripe in the leaf directory
 * For decoder case, buildOneInput is used to return the input
 * stream for a specific block of a file.
 */

public class DirectoryStripeReader extends StripeReader {
  /* source directory */
  Path srcDir;
  /* list of file status under source directory */
  List<FileStatus> lfs;
  /* current stripe Idx */
  long curStripeIdx;
  /* total number of stripe */
  long totalStripe;
  /* the block size of parity file */
  long parityBlockSize;
  
  public static class BlockInfo {
    public int fileIdx;
    public int blockId;
    public BlockInfo(int fileIdx, int blockId) {
      this.fileIdx = fileIdx;
      this.blockId = blockId;
    }
  }
  List<BlockInfo> stripeBlocks = null;
  
  public static long getParityBlockSize(Configuration conf,
      List<FileStatus> lfs) {
    long parityBlockSize = 0L; 
    for (FileStatus fsStat: lfs) {
      long size = Math.min(fsStat.getBlockSize(), fsStat.getLen());
      if ( size > parityBlockSize) {
        parityBlockSize = size;
      }
    }
    int bytesPerChecksum = conf.getInt("io.bytes.per.checksum", 512);
    if (parityBlockSize % bytesPerChecksum != 0) {
      // block size need to be the multiple of bytesPerChecksum;
      parityBlockSize = parityBlockSize / bytesPerChecksum * bytesPerChecksum
          + bytesPerChecksum;
    }
    return parityBlockSize;
  }
  
  public static long getBlockNum(List<FileStatus> lfs) {
    long blockNum = 0L;
    for (FileStatus fsStat: lfs) {
      blockNum += RaidNode.getNumBlocks(fsStat);
    }
    return blockNum;
  }
  
  public DirectoryStripeReader(Configuration conf, Codec codec,
      FileSystem fs, long stripeStartIdx,
      Path srcDir, List<FileStatus> lfs) 
      throws IOException {
    super(conf, codec, fs, stripeStartIdx);
    if (lfs == null) {
      throw new IOException("Couldn't get files under directory " + srcDir);
    }
    this.parityBlockSize = getParityBlockSize(conf, lfs);
    this.srcDir = srcDir;
    this.lfs = lfs;
    this.stripeBlocks = new ArrayList<BlockInfo>();
    long blockNum = 0L;
    for (int fid = 0; fid < lfs.size(); fid++) {
      FileStatus fsStat = lfs.get(fid);
      long numBlock = RaidNode.getNumBlocks(fsStat);
      blockNum += numBlock;
      for (int bid = 0; bid < numBlock; bid++) {
        stripeBlocks.add(new BlockInfo(fid, bid));
      }
    }
    totalStripe = RaidNode.numStripes(blockNum, codec.stripeLength);
    if (stripeStartIdx >= totalStripe) {
      throw new IOException("stripe start idx " + stripeStartIdx + 
          " is equal or larger than total stripe number " + totalStripe);
    }
    curStripeIdx = stripeStartIdx;
  }

  @Override
  public boolean hasNext() {
    return curStripeIdx < totalStripe;
  }

  @Override
  public InputStream[] getNextStripeInputs() throws IOException {
    InputStream[] blocks = new InputStream[codec.stripeLength];
    try {
      int startOffset = (int)curStripeIdx * codec.stripeLength;
      for (int i = 0; i < codec.stripeLength; i++) {
        if (startOffset + i < this.stripeBlocks.size()) {
          BlockInfo bi = this.stripeBlocks.get(startOffset + i);
          FileStatus curFile = lfs.get(bi.fileIdx);
          long seekOffset = bi.blockId * curFile.getBlockSize();
          Path srcFile = curFile.getPath();
          FSDataInputStream in = fs.open(srcFile, bufferSize);
          in.seek(seekOffset);
          LOG.info("Opening stream at " + srcFile + ":" + seekOffset);
          blocks[i] = in;
        } else {
          LOG.info("Using zeros at block " + i);
          // We have no src data at this offset.
          blocks[i] = new RaidUtils.ZeroInputStream(parityBlockSize);
        }
      }
      curStripeIdx++;
      return blocks;
    } catch (IOException e) {
      // If there is an error during opening a stream, close the previously
      // opened streams and re-throw.
      RaidUtils.closeStreams(blocks);
      throw e;
    }
  }
  
  @Override
  public InputStream buildOneInput(
      int locationIndex, long offsetInBlock,
      FileSystem srcFs, Path srcFile, FileStatus srcStat,
      FileSystem parityFs, Path parityFile, FileStatus parityStat
      ) throws IOException {
    final long blockSize = srcStat.getBlockSize();

    LOG.info("buildOneInput srcfile " + srcFile + " srclen " + srcStat.getLen() + 
        " parityfile " + parityFile + " paritylen " + parityStat.getLen() +
        " stripeindex " + stripeStartIdx + " locationindex " + locationIndex +
        " offsetinblock " + offsetInBlock);
    if (locationIndex < codec.parityLength) {
      return this.getParityFileInput(locationIndex, parityFile,
          parityFs, parityStat, offsetInBlock);
    } else {
      // Dealing with a src file here.
      int blockIdxInStripe = locationIndex - codec.parityLength;
      int curBlockIdx = (int)curStripeIdx * codec.stripeLength + blockIdxInStripe;
      if (curBlockIdx >= this.stripeBlocks.size()) {
        LOG.info("Using zeros because we reach the end of the stripe");
        return new RaidUtils.ZeroInputStream(blockSize * (curBlockIdx + 1));
      }
      BlockInfo bi = this.stripeBlocks.get(curBlockIdx);
      FileStatus fstat = lfs.get(bi.fileIdx);
      long offset = fstat.getBlockSize() * bi.blockId +
          offsetInBlock;
      if (offset >= fstat.getLen()) {
        LOG.info("Using zeros for " + fstat.getPath() + ":" + offset +
          " for location " + locationIndex);
        return new RaidUtils.ZeroInputStream(blockSize * (curBlockIdx + 1));
      } else {
        LOG.info("Opening " + fstat.getPath() + ":" + offset +
                 " for location " + locationIndex);
        FSDataInputStream s = fs.open(
            fstat.getPath(), conf.getInt("io.file.buffer.size", 64 * 1024));
        s.seek(offset);
        return s;
      }
    }
  }

}
