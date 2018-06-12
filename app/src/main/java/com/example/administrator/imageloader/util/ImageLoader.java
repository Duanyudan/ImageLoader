package com.example.administrator.imageloader.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 图片加载工具类
 * Created by duanyudan on 2018/6/12.
 */

public class ImageLoader {
    private static ImageLoader INSTANCE;
    //   图片缓存的核心对象
    private LruCache<String, Bitmap> mBitmapLruCache;
    //    线程池
    private ExecutorService mThreadPool;
    //    默认线程数
    private static final int DEAFULT_THREAD_COUNT = 1;
    //    加载策略
    private Type mType = Type.LIFO;

    public enum Type {
        FIFO, LIFO;
    }

    //   信号量 防止调用后台轮询线程时还没有未初始化，从而产生错误
    private Semaphore mSemaphorePoolThreadHandler = new Semaphore(0);
    //    利用信号量控制任务队列
    private Semaphore mSemaphorePoolThread;
    //任务队列
    private LinkedList<Runnable> mTaskQueue;
    //    后台轮询线程
    private Thread mPoolThread;
    //    轮询线程调度handler
    private Handler mPoolThreadHandler;
    //    更新Ui的handler
    private Handler mUIHandler;

    private ImageLoader(int threadCount, Type type) {
        init(threadCount, type);
    }

    public static ImageLoader getInstance() {
        if (INSTANCE == null) {
            synchronized (ImageLoader.class) {
                if (INSTANCE == null)
                    INSTANCE = new ImageLoader(DEAFULT_THREAD_COUNT, Type.LIFO);
            }
        }
        return INSTANCE;
    }

    /**
     * 初始化操作
     *
     * @param threadCount
     * @param type
     */
    private void init(int threadCount, Type type) {
//        后台轮询线程 handler looper messager
        mPoolThread = new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mPoolThreadHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
//                        线程池取出任务去执行
                        if (msg.what == 0) {
                            mThreadPool.execute(getTask());
                            try {
                                mSemaphorePoolThread.acquire();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                };
                mSemaphorePoolThreadHandler.release();
                Looper.loop();
            }
        };
        mPoolThread.start();

        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheMemory = maxMemory / 8;
        mBitmapLruCache = new LruCache<String, Bitmap>(cacheMemory) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight();
            }
        };

        mThreadPool = Executors.newFixedThreadPool(threadCount);
        mTaskQueue = new LinkedList<>();
        mSemaphorePoolThread = new Semaphore(threadCount);
    }

    private Runnable getTask() {
        if (mType == Type.FIFO) {
            return mTaskQueue.removeFirst();
        } else
            return mTaskQueue.removeLast();
    }

    public void loadImage(final String path, final ImageView imageView) {
//        防止调用多次imageview造成混乱
        imageView.setTag(path);
        if (mUIHandler == null) {
            mUIHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
//            回调设置图片
                    ImageHolder holder = (ImageHolder) msg.obj;
                    if (imageView.getTag().toString().equals(holder.path)) {
                        imageView.setImageBitmap(holder.bitmap);
                    }
                }
            };

            final Bitmap bitmap = getBitmapFromLruCache(path);
            if (bitmap != null) {
//                加载缓存图片
                refreshBitmap(bitmap, path);
                mSemaphorePoolThread.release();
            } else {
//                异步加载网络图片
                addTask(new Runnable() {
                    @Override
                    public void run() {
//                        图片压缩  获得图片需要显示的大小
                        ImageSize imageSize = getImageviewSize(imageView);
//                        压缩图片
                        Bitmap bm = decodeBitmapFromPath(path, imageSize.width, imageSize.height);
//                          将图片加入到缓存
                        addBitmapToLruCache(path, bm);
                        refreshBitmap(bitmap, path);
                        mSemaphorePoolThread.release();
                    }
                });
            }

        }
    }

    /**
     * 更新图片
     *
     * @param bitmap
     * @param path
     */
    private void refreshBitmap(Bitmap bitmap, String path) {
        Message message = Message.obtain();
        ImageHolder imageHolder = new ImageHolder();
        imageHolder.bitmap = bitmap;
        imageHolder.path = path;
        message.obj = imageHolder;
        mUIHandler.sendMessage(message);
    }

    private void addBitmapToLruCache(String path, Bitmap bm) {
        if (getBitmapFromLruCache(path) != null)
            return;
        mBitmapLruCache.put(path, bm);
    }

    /**
     * 压缩图片
     *
     * @param path
     * @param width
     * @param height
     * @return
     */
    private Bitmap decodeBitmapFromPath(String path, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;//获取bitmap宽高 但是不加载到内存中
        BitmapFactory.decodeFile(path, options);
        options.inSampleSize = caculateInSampleSize(options, width, height);
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        return bitmap;
    }

    private int caculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int width = options.outWidth;
        int height = options.outHeight;
        int inSampleSize = 1;
        if (width > reqWidth || height > reqHeight) {
            inSampleSize = Math.max(Math.round(width * 1.0f / reqWidth), Math.round(height * 1.0f / reqHeight));
        }
        return inSampleSize;
    }

    private class ImageSize {
        int width;
        int height;
    }

    private ImageSize getImageviewSize(ImageView imageView) {
        ImageSize imageSize = new ImageSize();
        ViewGroup.LayoutParams lp = imageView.getLayoutParams();
        int width = imageView.getWidth();//获取Imageview实际宽度
        if (width <= 0) {
//        获取imageview在layout中申明的宽度
            width = lp.width;
        }
        if (width <= 0) {
//            未设置固定值
            width = imageView.getMaxWidth();
        }
        DisplayMetrics displayMetrics = imageView.getContext().getResources().getDisplayMetrics();
        if (width <= 0) {
//            设置为屏幕宽度
            width = displayMetrics.widthPixels;
        }
        int height = imageView.getHeight();//获取Imageview实际宽度
        if (height <= 0) {
//        获取imageview在layout中申明的宽度
            height = lp.height;
        }
        if (height <= 0) {
//            未设置固定值
            height = imageView.getMaxHeight();
        }
        if (height <= 0) {
//            设置为屏幕宽度
            height = displayMetrics.heightPixels;
        }
        imageSize.width = width;
        imageSize.height = height;
        return imageSize;
    }

    /**
     * 同步方法块是防止mSemaphorePoolThreadHandler在不同的线程acquire 造成死锁状态，解决并发
     * mTaskQueue.add(runnable)也需要同步
     *
     * @param runnable
     */
    private synchronized void addTask(Runnable runnable) {
        mTaskQueue.add(runnable);
        try {
//            注意 mPoolThreadHandler 是在后台线程初始化的  无法保证在另一个线程是否为空
            if (mPoolThreadHandler == null)
                mSemaphorePoolThreadHandler.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mPoolThreadHandler.sendEmptyMessage(0);
    }

    /**
     * 从缓存中获取bitmap
     *
     * @param key
     * @return
     */
    private Bitmap getBitmapFromLruCache(String key) {
        return mBitmapLruCache.get(key);
    }

    private class ImageHolder {
        String path;
        Bitmap bitmap;
    }
}
