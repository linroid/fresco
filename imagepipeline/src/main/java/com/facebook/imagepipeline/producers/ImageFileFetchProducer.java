package com.facebook.imagepipeline.producers;

import com.facebook.binaryresource.BinaryResource;
import com.facebook.binaryresource.FileBinaryResource;
import com.facebook.cache.common.CacheKey;
import com.facebook.imagepipeline.cache.DefaultCacheKeyFactory;
import com.facebook.imagepipeline.core.ImagePipelineFactory;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.request.ImageRequest;

import java.io.File;

/**
 * @author zlin @ Zhihu Inc.
 * @since 12-22-2015
 */
public class ImageFileFetchProducer implements Producer<File>
{
	private final Producer<EncodedImage> mInputProducer;

	public ImageFileFetchProducer(final Producer<EncodedImage> pInputProducer)
	{
		mInputProducer = pInputProducer;
	}

	@Override
	public void produceResults(final Consumer<File> consumer, final ProducerContext context)
	{
		mInputProducer.produceResults(new ImageFileFetchConsumer(consumer, context.getImageRequest()), context);
	}

	private static class ImageFileFetchConsumer extends DelegatingConsumer<EncodedImage, File>
	{
		ImageRequest mImageRequest;

		private ImageFileFetchConsumer(Consumer<File> consumer, ImageRequest imageRequest)
		{
			super(consumer);
			mImageRequest = imageRequest;
		}

		@Override
		protected void onNewResultImpl(EncodedImage newResult, boolean isLast)
		{
			File localFile = null;
			CacheKey cacheKey = DefaultCacheKeyFactory.getInstance().getEncodedCacheKey(mImageRequest);
			BinaryResource resource = null;
			if (ImagePipelineFactory.getInstance().getMainDiskStorageCache().hasKey(cacheKey))
			{
				resource = ImagePipelineFactory.getInstance().getMainDiskStorageCache().getResource(cacheKey);
			}
			else if (ImagePipelineFactory.getInstance().getSmallImageDiskStorageCache().hasKey(cacheKey))
			{
				resource = ImagePipelineFactory.getInstance().getSmallImageDiskStorageCache().getResource(cacheKey);
			}
			if (resource != null)
			{
				localFile = ((FileBinaryResource) resource).getFile();
			}
			if (localFile != null && localFile.exists())
			{
				getConsumer().onNewResult(localFile, isLast);
			}
			else
			{
				getConsumer().onFailure(new IllegalStateException("cache file not found"));
			}
		}
	}
}
