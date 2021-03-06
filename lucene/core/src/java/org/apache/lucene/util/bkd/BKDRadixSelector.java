/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util.bkd;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FutureArrays;
import org.apache.lucene.util.RadixSelector;


/**
 *
 * Offline Radix selector for BKD tree.
 *
 *  @lucene.internal
 * */
public final class BKDRadixSelector {
  //size of the histogram
  private static final int HISTOGRAM_SIZE = 256;
  //size of the online buffer: 8 KB
  private static final int MAX_SIZE_OFFLINE_BUFFER = 1024 * 8;
  // we store one histogram per recursion level
  private final long[][] histogram;
  //bytes per dimension
  private final int bytesPerDim;
  // number of bytes to be sorted: bytesPerDim + Integer.BYTES
  private final int bytesSorted;
  //data dimensions size
  private final int packedBytesLength;
  //flag to when we are moving to sort on heap
  private final int maxPointsSortInHeap;
  //reusable buffer
  private final byte[] offlineBuffer;
  //holder for partition points
  private final int[] partitionBucket;
  // scratch object to move bytes around
  private final BytesRef bytesRef1 = new BytesRef();
  // scratch object to move bytes around
  private final BytesRef bytesRef2 = new BytesRef();
  //Directory to create new Offline writer
  private final Directory tempDir;
  // prefix for temp files
  private final String tempFileNamePrefix;

  /**
   * Sole constructor.
   */
  public BKDRadixSelector(int numDim, int bytesPerDim, int maxPointsSortInHeap, Directory tempDir, String tempFileNamePrefix) {
    this.bytesPerDim = bytesPerDim;
    this.packedBytesLength = numDim * bytesPerDim;
    this.bytesSorted = bytesPerDim + Integer.BYTES;
    this.maxPointsSortInHeap = 2 * maxPointsSortInHeap;
    int numberOfPointsOffline  = MAX_SIZE_OFFLINE_BUFFER / (packedBytesLength + Integer.BYTES);
    this.offlineBuffer = new byte[numberOfPointsOffline * (packedBytesLength + Integer.BYTES)];
    this.partitionBucket = new int[bytesSorted];
    this.histogram = new long[bytesSorted][HISTOGRAM_SIZE];
    this.bytesRef1.length = numDim * bytesPerDim;
    this.tempDir = tempDir;
    this.tempFileNamePrefix = tempFileNamePrefix;
  }

  /**
   *
   * Method to partition the input data. It returns the value of the dimension where
   * the split happens. The method destroys the original writer.
   *
   */
  public byte[] select(PointWriter points, PointWriter left, PointWriter right, long from, long to, long partitionPoint, int dim) throws IOException {
    checkArgs(from, to, partitionPoint);

    //If we are on heap then we just select on heap
    if (points instanceof HeapPointWriter) {
      return heapSelect((HeapPointWriter) points, left, right, dim, Math.toIntExact(from), Math.toIntExact(to),  Math.toIntExact(partitionPoint), 0);
    }

    //reset histogram
    for (int i = 0; i < bytesSorted; i++) {
      Arrays.fill(histogram[i], 0);
    }
    OfflinePointWriter offlinePointWriter = (OfflinePointWriter) points;

    //find common prefix, it does already set histogram values if needed
    int commonPrefix = findCommonPrefix(offlinePointWriter, from, to, dim);

    //if all equals we just partition the data
    if (commonPrefix ==  bytesSorted) {
      partition(offlinePointWriter, left,  right, null, from, to, dim, commonPrefix - 1, partitionPoint);
      return partitionPointFromCommonPrefix();
    }
    //let's rock'n'roll
    return buildHistogramAndPartition(offlinePointWriter, left, right, from, to, partitionPoint, 0, commonPrefix, dim);
  }

  void checkArgs(long from, long to, long partitionPoint) {
    if (partitionPoint < from) {
      throw new IllegalArgumentException("partitionPoint must be >= from");
    }
    if (partitionPoint >= to) {
      throw new IllegalArgumentException("partitionPoint must be < to");
    }
  }

  private int findCommonPrefix(OfflinePointWriter points, long from, long to, int dim) throws IOException{
    //find common prefix
    byte[] commonPrefix = new byte[bytesSorted];
    int commonPrefixPosition = bytesSorted;
    try (OfflinePointReader reader = points.getReader(from, to - from, offlineBuffer)) {
      reader.next();
      reader.packedValueWithDocId(bytesRef1);
      // copy dimension
      System.arraycopy(bytesRef1.bytes, bytesRef1.offset + dim * bytesPerDim, commonPrefix, 0, bytesPerDim);
      // copy docID
      System.arraycopy(bytesRef1.bytes, bytesRef1.offset + packedBytesLength, commonPrefix, bytesPerDim, Integer.BYTES);
      for (long i = from + 1; i< to; i++) {
        reader.next();
        reader.packedValueWithDocId(bytesRef1);
        int startIndex =  dim * bytesPerDim;
        int endIndex  = (commonPrefixPosition > bytesPerDim) ? startIndex + bytesPerDim :  startIndex + commonPrefixPosition;
        int j = FutureArrays.mismatch(commonPrefix, 0, endIndex - startIndex, bytesRef1.bytes, bytesRef1.offset + startIndex, bytesRef1.offset + endIndex);
        if (j == 0) {
          return 0;
        } else if (j == -1) {
          if (commonPrefixPosition > bytesPerDim) {
            //tie-break on docID
            int k = FutureArrays.mismatch(commonPrefix, bytesPerDim, commonPrefixPosition, bytesRef1.bytes, bytesRef1.offset + packedBytesLength, bytesRef1.offset + packedBytesLength + commonPrefixPosition - bytesPerDim );
            if (k != -1) {
              commonPrefixPosition = bytesPerDim + k;
            }
          }
        } else {
          commonPrefixPosition = j;
        }
      }
    }

    //build histogram up to the common prefix
    for (int i = 0; i < commonPrefixPosition; i++) {
      partitionBucket[i] = commonPrefix[i] & 0xff;
      histogram[i][partitionBucket[i]] = to - from;
    }
    return commonPrefixPosition;
  }

  private byte[] buildHistogramAndPartition(OfflinePointWriter points, PointWriter left, PointWriter right,
                                            long from, long to, long partitionPoint, int iteration,  int commonPrefix, int dim) throws IOException {

    long leftCount = 0;
    long rightCount = 0;
    //build histogram at the commonPrefix byte
    try (OfflinePointReader reader = points.getReader(from, to - from, offlineBuffer)) {
      while (reader.next()) {
        reader.packedValueWithDocId(bytesRef1);
        int bucket;
        if (commonPrefix < bytesPerDim) {
          bucket = bytesRef1.bytes[bytesRef1.offset + dim * bytesPerDim + commonPrefix] & 0xff;
        } else {
          bucket = bytesRef1.bytes[bytesRef1.offset + packedBytesLength + commonPrefix - bytesPerDim] & 0xff;
        }
        histogram[commonPrefix][bucket]++;
      }
    }
    //Count left points and record the partition point
    for(int i = 0; i < HISTOGRAM_SIZE; i++) {
      long size = histogram[commonPrefix][i];
      if (leftCount + size > partitionPoint - from) {
        partitionBucket[commonPrefix] = i;
        break;
      }
      leftCount += size;
    }
    //Count right points
    for(int i = partitionBucket[commonPrefix] + 1; i < HISTOGRAM_SIZE; i++) {
      rightCount += histogram[commonPrefix][i];
    }

    long delta = histogram[commonPrefix][partitionBucket[commonPrefix]];
    assert leftCount + rightCount + delta == to - from;

    //special case when be have lot of points that are equal
    if (commonPrefix == bytesSorted - 1) {
      long tieBreakCount =(partitionPoint - from - leftCount);
      partition(points, left,  right, null, from, to, dim, commonPrefix, tieBreakCount);
      return partitionPointFromCommonPrefix();
    }

    //create the delta points writer
    PointWriter deltaPoints;
    if (delta <= maxPointsSortInHeap) {
      deltaPoints =  new HeapPointWriter(Math.toIntExact(delta), Math.toIntExact(delta), packedBytesLength);
    } else {
      deltaPoints = new OfflinePointWriter(tempDir, tempFileNamePrefix, packedBytesLength, "delta" + iteration, delta);
    }
    //divide the points. This actually destroys the current writer
    partition(points, left, right, deltaPoints, from, to, dim, commonPrefix, 0);
    //close delta point writer
    deltaPoints.close();

    long newPartitionPoint = partitionPoint - from - leftCount;

    if (deltaPoints instanceof HeapPointWriter) {
      return heapSelect((HeapPointWriter) deltaPoints, left, right, dim, 0, (int) deltaPoints.count(), Math.toIntExact(newPartitionPoint), ++commonPrefix);
    } else {
      return buildHistogramAndPartition((OfflinePointWriter) deltaPoints, left, right, 0, deltaPoints.count(), newPartitionPoint, ++iteration, ++commonPrefix, dim);
    }
  }

  private void partition(OfflinePointWriter points, PointWriter left, PointWriter right, PointWriter deltaPoints,
                           long from, long to, int dim, int bytePosition, long numDocsTiebreak) throws IOException {
    assert bytePosition == bytesSorted -1 || deltaPoints != null;
    long tiebreakCounter = 0;
    try (OfflinePointReader reader = points.getReader(from, to - from, offlineBuffer)) {
      while (reader.next()) {
        reader.packedValueWithDocId(bytesRef1);
        reader.packedValue(bytesRef2);
        int docID = reader.docID();
        int bucket;
        if (bytePosition < bytesPerDim) {
          bucket = bytesRef1.bytes[bytesRef1.offset + dim * bytesPerDim + bytePosition] & 0xff;
        } else {
          bucket = bytesRef1.bytes[bytesRef1.offset + packedBytesLength + bytePosition - bytesPerDim] & 0xff;
        }
        //int bucket = getBucket(bytesRef1, dim, thisCommonPrefix);
        if (bucket < this.partitionBucket[bytePosition]) {
          // to the left side
          left.append(bytesRef2, docID);
        } else if (bucket > this.partitionBucket[bytePosition]) {
          // to the right side
          right.append(bytesRef2, docID);
        } else {
          if (bytePosition == bytesSorted - 1) {
            if (tiebreakCounter < numDocsTiebreak) {
              left.append(bytesRef2, docID);
              tiebreakCounter++;
            } else {
              right.append(bytesRef2, docID);
            }
          } else {
            deltaPoints.append(bytesRef2, docID);
          }
        }
      }
    }
    //Delete original file
    points.destroy();
  }

  private byte[] partitionPointFromCommonPrefix() {
    byte[] partition = new byte[bytesPerDim];
    for (int i = 0; i < bytesPerDim; i++) {
      partition[i] = (byte)partitionBucket[i];
    }
    return partition;
  }

  private byte[] heapSelect(HeapPointWriter points, PointWriter left, PointWriter right, int dim, int from, int to, int partitionPoint, int commonPrefix) throws IOException {
    final int offset = dim * bytesPerDim + commonPrefix;
    new RadixSelector(bytesSorted - commonPrefix) {

      @Override
      protected void swap(int i, int j) {
        points.swap(i, j);
      }

      @Override
      protected int byteAt(int i, int k) {
        assert k >= 0;
        if (k + commonPrefix < bytesPerDim) {
          // dim bytes
          int block = i / points.valuesPerBlock;
          int index = i % points.valuesPerBlock;
          return points.blocks.get(block)[index * packedBytesLength + offset + k] & 0xff;
        } else {
          // doc id
          int s = 3 - (k + commonPrefix - bytesPerDim);
          return (points.docIDs[i] >>> (s * 8)) & 0xff;
        }
      }
    }.select(from, to, partitionPoint);

    for (int i = from; i < to; i++) {
      points.getPackedValueSlice(i, bytesRef1);
      int docID = points.docIDs[i];
      if (i < partitionPoint) {
        left.append(bytesRef1, docID);
      } else {
        right.append(bytesRef1, docID);
      }
    }
    byte[] partition = new byte[bytesPerDim];
    points.getPackedValueSlice(partitionPoint, bytesRef1);
    System.arraycopy(bytesRef1.bytes, bytesRef1.offset + dim * bytesPerDim, partition, 0, bytesPerDim);
    return partition;
  }
}
