/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import com.facebook.common.internal.Preconditions;
import com.facebook.common.logging.FLog;
import com.facebook.common.references.CloseableReference;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.memory.PooledByteBufferFactory;
import com.facebook.imagepipeline.memory.PooledByteStreams;
import com.facebook.binaryresource.BinaryResource;
import com.facebook.cache.common.CacheKey;
import com.facebook.cache.common.WriterCallback;
import com.facebook.cache.disk.FileCache;

import bolts.Task;

/**
 * BufferedDiskCache provides get and put operations to take care of scheduling disk-cache
 * read/writes.
 */
public class BufferedDiskCache {
  private static final Class<?> TAG = BufferedDiskCache.class;

  private final FileCache mFileCache;
  private final PooledByteBufferFactory mPooledByteBufferFactory;
  private final PooledByteStreams mPooledByteStreams;
  private final Executor mReadExecutor;
  private final Executor mWriteExecutor;
  private final StagingArea mStagingArea;
  private final ImageCacheStatsTracker mImageCacheStatsTracker;

  public BufferedDiskCache(
      FileCache fileCache,
      PooledByteBufferFactory pooledByteBufferFactory,
      PooledByteStreams pooledByteStreams,
      Executor readExecutor,
      Executor writeExecutor,
      ImageCacheStatsTracker imageCacheStatsTracker) {
    mFileCache = fileCache;
    mPooledByteBufferFactory = pooledByteBufferFactory;
    mPooledByteStreams = pooledByteStreams;
    mReadExecutor = readExecutor;
    mWriteExecutor = writeExecutor;
    mImageCacheStatsTracker = imageCacheStatsTracker;
    mStagingArea = StagingArea.getInstance();
  }

  /**
   * Performs a key-value look up in the disk cache. If the value is not found in the staging area,
   * then a disk cache check is scheduled on a background thread. Any error manifests itself as a
   * cache miss, i.e. the returned Task resolves to false.
   * @param key
   * @return Task that resolves to true if the element is found, or false otherwise
   */
  public Task<Boolean> contains(final CacheKey key) {
    Preconditions.checkNotNull(key);

    final EncodedImage pinnedImage = mStagingArea.get(key);
    if (pinnedImage != null) {
      pinnedImage.close();
      FLog.v(TAG, "Found image for %s in staging area", key.toString());
      mImageCacheStatsTracker.onStagingAreaHit();
      return Task.forResult(true);
    }

    try {
      return Task.call(
          new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
              EncodedImage result = mStagingArea.get(key);
              if (result != null) {
                result.close();
                FLog.v(TAG, "Found image for %s in staging area", key.toString());
                mImageCacheStatsTracker.onStagingAreaHit();
                return true;
              } else {
                FLog.v(TAG, "Did not find image for %s in staging area", key.toString());
                mImageCacheStatsTracker.onStagingAreaMiss();
                try {
                  return mFileCache.hasKey(key);
                } catch (Exception exception) {
                  return false;
                }
              }
            }
          },
          mReadExecutor);
    } catch (Exception exception) {
      // Log failure
      // TODO: 3697790
      FLog.w(
          TAG,
          exception,
          "Failed to schedule disk-cache read for %s",
          key.toString());
      return Task.forError(exception);
    }
  }

  /**
   * Performs key-value look up in disk cache. If value is not found in disk cache staging area
   * then disk cache read is scheduled on background thread. Any error manifests itself as
   * cache miss, i.e. the returned future resolves to null.
   * @param key
   * @return ListenableFuture that resolves to cached element or null if one cannot be retrieved;
   *   returned future never rethrows any exception
   */
  public Task<EncodedImage> get(final CacheKey key, final AtomicBoolean isCancelled) {
    Preconditions.checkNotNull(key);
    Preconditions.checkNotNull(isCancelled);

    final EncodedImage pinnedImage = mStagingArea.get(key);
    if (pinnedImage != null) {
      FLog.v(TAG, "Found image for %s in staging area", key.toString());
      mImageCacheStatsTracker.onStagingAreaHit();
      return Task.forResult(pinnedImage);
    }

    try {
      return Task.call(
          new Callable<EncodedImage>() {
            @Override
            public EncodedImage call()
                throws Exception {
              if (isCancelled.get()) {
                throw new CancellationException();
              }
              EncodedImage result = mStagingArea.get(key);
              if (result != null) {
                FLog.v(TAG, "Found image for %s in staging area", key.toString());
                mImageCacheStatsTracker.onStagingAreaHit();
              } else {
                FLog.v(TAG, "Did not find image for %s in staging area", key.toString());
                mImageCacheStatsTracker.onStagingAreaMiss();

                try {
                  final PooledByteBuffer buffer = readFromDiskCache(key);
                  CloseableReference<PooledByteBuffer> ref = CloseableReference.of(buffer);
                  try {
                    result = new EncodedImage(ref);
                  } finally {
                    CloseableReference.closeSafely(ref);
                  }
                } catch (Exception exception) {
                  return null;
                }
              }

              if (Thread.interrupted()) {
                FLog.v(TAG, "Host thread was interrupted, decreasing reference count");
                if (result != null) {
                  result.close();
                }
                throw new InterruptedException();
              } else {
                return result;
              }
            }
          },
          mReadExecutor);
    } catch (Exception exception) {
      // Log failure
      // TODO: 3697790
      FLog.w(
          TAG,
          exception,
          "Failed to schedule disk-cache read for %s",
          key.toString());
      return Task.forError(exception);
    }
  }

  public void put(
	  final CacheKey key,
	  EncodedImage encodedImage) {
	  put(key, encodedImage, false);
  }
  /**
   * Associates encodedImage with given key in disk cache. Disk write is performed on background
   * thread, so the caller of this method is not blocked
   */
  public void put(
      final CacheKey key,
      EncodedImage encodedImage,
      boolean sync) {
    Preconditions.checkNotNull(key);
    Preconditions.checkArgument(EncodedImage.isValid(encodedImage));

    if (sync) {
      writeToDiskCache(key, encodedImage);
    }
    else
    {
      // Store encodedImage in staging area
      mStagingArea.put(key, encodedImage);

      // Write to disk cache. This will be executed on background thread, so increment the ref count.
      // When this write completes (with success/failure), then we will bump down the ref count
      // again.
      final EncodedImage finalEncodedImage = EncodedImage.cloneOrNull(encodedImage);
      try
      {
        mWriteExecutor.execute(new Runnable()
        {
          @Override
          public void run()
          {
            try
            {
              writeToDiskCache(key, finalEncodedImage);
            }
            finally
            {
              mStagingArea.remove(key, finalEncodedImage);
              EncodedImage.closeSafely(finalEncodedImage);
            }
          }
        });
      }
      catch (Exception exception)
      {
        // We failed to enqueue cache write. Log failure and decrement ref count
        // TODO: 3697790
        FLog.w(TAG, exception, "Failed to schedule disk-cache write for %s", key.toString());
        mStagingArea.remove(key, encodedImage);
        EncodedImage.closeSafely(finalEncodedImage);
      }
    }
  }

  /**
   * Removes the item from the disk cache and the staging area.
   */
  public Task<Void> remove(final CacheKey key) {
    Preconditions.checkNotNull(key);
    mStagingArea.remove(key);
    try {
      return Task.call(
          new Callable<Void>() {
            @Override
            public Void call() throws Exception {
              mStagingArea.remove(key);
              mFileCache.remove(key);
              return null;
            }
          },
          mWriteExecutor);
    } catch (Exception exception) {
      // Log failure
      // TODO: 3697790
      FLog.w(TAG, exception, "Failed to schedule disk-cache remove for %s", key.toString());
      return Task.forError(exception);
    }
  }

  /**
   * Clears the disk cache and the staging area.
   */
  public Task<Void> clearAll() {
    mStagingArea.clearAll();
    try {
      return Task.call(
          new Callable<Void>() {
            @Override
            public Void call() throws Exception {
              mStagingArea.clearAll();
              mFileCache.clearAll();
              return null;
            }
          },
          mWriteExecutor);
    } catch (Exception exception) {
      // Log failure
      // TODO: 3697790
      FLog.w(TAG, exception, "Failed to schedule disk-cache clear");
      return Task.forError(exception);
    }
  }

  /**
   * Performs disk cache read. In case of any exception null is returned.
   */
  private PooledByteBuffer readFromDiskCache(final CacheKey key) throws IOException {
    try {
      FLog.v(TAG, "Disk cache read for %s", key.toString());

      final BinaryResource diskCacheResource = mFileCache.getResource(key);
      if (diskCacheResource == null) {
        FLog.v(TAG, "Disk cache miss for %s", key.toString());
        mImageCacheStatsTracker.onDiskCacheMiss();
        return null;
      } else {
        FLog.v(TAG, "Found entry in disk cache for %s", key.toString());
        mImageCacheStatsTracker.onDiskCacheHit();
      }

      PooledByteBuffer byteBuffer;
      final InputStream is = diskCacheResource.openStream();
      try {
        byteBuffer = mPooledByteBufferFactory.newByteBuffer(is, (int) diskCacheResource.size());
      } finally {
        is.close();
      }

      FLog.v(TAG, "Successful read from disk cache for %s", key.toString());
      return byteBuffer;
    } catch (IOException ioe) {
      // TODO: 3697790 log failures
      // TODO: 5258772 - uncomment line below
      // mFileCache.remove(key);
      FLog.w(TAG, ioe, "Exception reading from cache for %s", key.toString());
      mImageCacheStatsTracker.onDiskCacheGetFail();
      throw ioe;
    }
  }

  /**
   * Writes to disk cache
   * @throws IOException
   */
  private void writeToDiskCache(
      final CacheKey key,
      final EncodedImage encodedImage) {
    FLog.v(TAG, "About to write to disk-cache for key %s", key.toString());
    try {
      mFileCache.insert(
          key, new WriterCallback() {
            @Override
            public void write(OutputStream os) throws IOException {
              mPooledByteStreams.copy(encodedImage.getInputStream(), os);
            }
          }
      );
      FLog.v(TAG, "Successful disk-cache write for key %s", key.toString());
    } catch (IOException ioe) {
      // Log failure
      // TODO: 3697790
      FLog.w(TAG, ioe, "Failed to write to disk-cache for key %s", key.toString());
    }
  }
}
