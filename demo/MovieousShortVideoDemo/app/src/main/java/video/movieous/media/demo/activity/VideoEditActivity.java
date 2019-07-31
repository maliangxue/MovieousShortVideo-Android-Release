package video.movieous.media.demo.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import video.movieous.engine.UAVOptions;
import video.movieous.engine.UAudioMixClip;
import video.movieous.engine.UMediaTrimTime;
import video.movieous.engine.UVideoSaveListener;
import video.movieous.engine.base.callback.SingleCallback;
import video.movieous.engine.base.utils.ULog;
import video.movieous.engine.media.util.MediaUtil;
import video.movieous.engine.view.UFitViewHelper;
import video.movieous.engine.view.UPaintView;
import video.movieous.engine.view.UTextureView;
import video.movieous.media.demo.R;
import video.movieous.media.demo.activity.base.BaseEditActivity;
import video.movieous.media.demo.utils.UriUtil;
import video.movieous.shortvideo.*;

import java.util.List;

/**
 * VideoEditActivity
 */
public class VideoEditActivity extends BaseEditActivity implements UVideoSaveListener {
    private static final String TAG = "VideoEditActivity";
    private static final int REQUEST_CODE_CHOOSE_MUSIC = 2;
    public static final String VIDEO_PATH = "video_path";
    private static final String OUT_FILE = "/sdcard/movieous/shortvideo/video_edit_test.mp4";

    private UTextureView mRenderView;
    private ImageView mPreviewImage;
    private Button mRecordButton;
    private Button mSaveButton;
    private TextView mTvTip;
    private ImageButton mPlayButton;

    private UVideoEditManager mVideoEditManager;
    private USticker mSticker;
    private USticker mTextSticker;
    private UPaintView mPaintView;
    private long mStartTime;
    private int mVideoWidth;
    private int mVideoHeight;
    private long mVideoDuration;
    private boolean mTouchingTextureView;
    private boolean mIsOverlayVideoAdded = true;
    protected VideoEditorState mEditorState = VideoEditorState.Idle;
    private int mPreviewWidth;
    private int mPreviewHeight;

    protected enum VideoEditorState {
        Idle,
        Playing,
        Paused,
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initView();
        mInputFile = getIntent().getStringExtra(VIDEO_PATH);
        if (TextUtils.isEmpty(mInputFile)) {
            startFileSelectActivity(this, false, 1);
        } else {
            getFile(mInputFile);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoEditManager != null) {
            startPlayback();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoEditManager != null) {
            pausePlayback();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mVideoEditManager != null) {
            stopPlayback();
            mVideoEditManager.release();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_CHOOSE_MUSIC) {
            String selectedFilepath = UriUtil.getPath(this, data.getData());
            Log.i(TAG, "Select file: " + selectedFilepath);
            if (selectedFilepath != null && !"".equals(selectedFilepath)) {
                mVideoEditManager.setMusicFile(selectedFilepath);
                mVideoEditManager.setMusicPositionMs(60 * 1000);
                mVideoEditManager.setOriginVolume(0);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initView() {
        setContentView(R.layout.activity_video_edit);
        mRenderView = $(R.id.render_view);
        mPreviewImage = $(R.id.preview_image);
        mRecordButton = $(R.id.record);
        mTvTip = $(R.id.tv_tip);
        mPlayButton = $(R.id.pause_playback);
        mSaveButton = $(R.id.save);
        mRenderView.setScaleType(UFitViewHelper.ScaleType.CENTER_INSIDE);

        $(R.id.capture).setOnClickListener(view -> mVideoEditManager.captureVideoFrame(bitmap -> runOnUiThread(() -> mPreviewImage.setImageBitmap(bitmap))));

        $(R.id.debug_on).setOnClickListener(v -> {
            if (v.getTag() == null) {
                UShortVideoEnv.setLogLevel(ULog.D);
                v.setTag(0);
            } else {
                UShortVideoEnv.setLogLevel(ULog.I);
                v.setTag(null);
            }
        });

        $(R.id.get_thumb_img).setOnClickListener(v -> {
            if (v.getTag() == null) {
                v.setTag(0);
            } else {
                v.setTag(null);
            }
            boolean isKeyframe = v.getTag() != null;
            UMediaUtil.getVideoThumb(Uri.parse(mInputFile), 7, 0l, mVideoDuration, 98, 98, isKeyframe, new SingleCallback() {
                @Override
                public void onSingleCallback(Object bitmap, Object pts) {
                    runOnUiThread(() -> {
                        mPreviewImage.setImageBitmap((Bitmap) bitmap);
                    });
                }
            });
        });

        $(R.id.pause_playback).setOnClickListener(v -> {
            v.setEnabled(false);
            if (mVideoEditManager.isPlaying()) {
                pausePlayback();
            } else {
                startPlayback();
            }
            v.setEnabled(true);
        });

        // 说明：编辑保存文件包含两种模式：
        // 1、离屏渲染，推荐 保存的处理操作和当前预览是分开的，速度更快
        // 2、直接录制当前预览画面显示的内容，可以分别摘取多个片段，然后进行合成保存，适用于边看边录制的模式
        mSaveButton.setOnClickListener(v -> {
            v.setEnabled(false);
            if (v.getTag() != null) {
                cancelSave();
            } else {
                v.setTag(1);
                mSaveButton.setText("取消");
                saveVideo();
            }
            v.setEnabled(true);
        });

        mRecordButton.setOnClickListener(v -> {
            if (mVideoEditManager.isRecording()) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        $(R.id.combine).setOnClickListener(v -> combineClip());

        // 背景音乐
        $(R.id.add_music).setOnClickListener(v -> startMusicActivity());

        // 文字特效
        $(R.id.add_text).setOnClickListener(view -> demoText());

        // MV 特效
        $(R.id.add_mv_file).setOnClickListener(view -> demoMv());

        // 涂鸦
        $(R.id.add_paintview).setOnClickListener(view -> demoGraffitiPaint());

        // 多段混音
        $(R.id.add_multi_audio_mix).setOnClickListener(view -> demoMultiAudioMix());

    }

    private void initVideoEditManager() {
        mVideoEditManager = new UVideoEditManager()
                .setVideoFrameListener(this)
                .setRecordEnabled(true, false)
                .init(mRenderView, mInputFile);
        setOutputSize();
    }

    private void setOutputSize() {
        if (mRenderView.getScaleType() == UFitViewHelper.ScaleType.CENTER_INSIDE) {
            UAVOptions options = new UAVOptions();
            int outWidth = 480;
            int outHeight = 848;
            options.setInteger(UAVOptions.KEY_VIDEO_WIDTH, outWidth);
            options.setInteger(UAVOptions.KEY_VIDEO_HEIGHT, outHeight);
            mVideoEditManager.setAVOptions(options)
                    .setBackgroundColor(Color.DKGRAY);
        }
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        mPreviewWidth = width;
        mPreviewHeight = height;
        super.onSurfaceChanged(width, height);
        setOutputSize();
    }

    private void startPlayback() {
        if (mEditorState == VideoEditorState.Playing) return;
        if (mEditorState == VideoEditorState.Idle) {
            mVideoEditManager.setVideoFrameListener(this);
            mVideoEditManager.start();
            mEditorState = VideoEditorState.Playing;
            Log.i(TAG, "startPlayback: start");
        } else if (mEditorState == VideoEditorState.Paused) {
            mVideoEditManager.resume();
            Log.i(TAG, "startPlayback: resume");
            mEditorState = VideoEditorState.Playing;
        }
        setPlayButtonState(R.drawable.btn_pause);
    }

    private void stopPlayback() {
        if (mEditorState == VideoEditorState.Idle) return;
        mVideoEditManager.stop();
        mEditorState = VideoEditorState.Idle;
        setPlayButtonState(R.drawable.btn_play);
    }

    private void pausePlayback() {
        if (mEditorState == VideoEditorState.Playing) {
            mVideoEditManager.pause();
            mEditorState = VideoEditorState.Paused;
            setPlayButtonState(R.drawable.btn_play);
        }
    }

    private void setPlayButtonState(int resId) {
        if (mPlayButton == null) return;
        runOnUiThread(() -> mPlayButton.setImageResource(resId));
    }

    private void startRecording() {
        mRecordButton.setText("停止");
        if (mVideoEditManager != null) {
            mVideoEditManager.startRecord();
        }
    }

    private void stopRecording() {
        mRecordButton.setText("录制");
        if (mVideoEditManager != null) {
            mVideoEditManager.stopRecord();
        }
    }

    // 合成录制片段
    private void combineClip() {
        if (mVideoEditManager != null) {
            mStartTime = System.currentTimeMillis();
            mVideoEditManager.combineClip(OUT_FILE, this);
        }
    }

    @Override
    protected void getFiles(List<String> fileList) {
        getFile(fileList.get(0));
    }

    private void getFile(String file) {
        mInputFile = file;
        MediaUtil.Metadata metadata = UMediaUtil.getMetadata(mInputFile);
        boolean needRotation = metadata.rotation / 90 % 2 != 0;
        mVideoWidth = needRotation ? metadata.height : metadata.width;
        mVideoHeight = needRotation ? metadata.width : metadata.height;
        mVideoDuration = metadata.duration;
        Log.i(TAG, "video w = " + mVideoWidth + ", h = " + mVideoHeight + ", rotation = " + metadata.rotation + ", duration = " + mVideoDuration);
        initVideoEditManager();
        startPlayback();
    }

    // 背景音乐选择
    private void startMusicActivity() {
        Intent intent = new Intent();
        if (Build.VERSION.SDK_INT < 19) {
            intent.setAction(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
        } else {
            intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
        }
        startActivityForResult(Intent.createChooser(intent, "请选择音乐文件："), REQUEST_CODE_CHOOSE_MUSIC);
    }

    // 文字水印
    private void demoText() {
        startPlayback();
        if (mTextSticker == null) {
            addTextSticker();
        } else {
            removeTextSticker();
        }
    }

    private void addTextSticker() {
        mTextSticker = new USticker();
        String stickerText = "这是一个美丽的传说";
        int stickerW = mVideoWidth / 2;
        int stickerH = stickerW / stickerText.length();
        mTextSticker.init(USticker.StickerType.TEXT, stickerW, stickerH)
                .setText(stickerText, Color.RED)
                .setDuration(0, (int) UMediaUtil.getMetadata(mInputFile).duration)
                .setPosition(mVideoWidth / 2 - stickerW / 2, mVideoHeight - stickerH - 20);     //视频图像的左上角为坐标原点
        mVideoEditManager.addSticker(mTextSticker);
    }

    private void removeTextSticker() {
        mVideoEditManager.removeSticker(mTextSticker);
        mTextSticker = null;
    }

    // MV 特效
    private void demoMv() {
        if (mIsOverlayVideoAdded) {
            addMv();
        } else {
            removeMv();
        }
    }

    private void addMv() {
        // 需要存在指定的 mv 特效文件
        String mvFile = "/sdcard/movieous/shortvideo/mv/mv.mp4";
        String maskFile = "/sdcard/movieous/shortvideo/mv/mv_alpha.mp4";
        mVideoEditManager.setOverlayVideoFile(mvFile, maskFile);
        mIsOverlayVideoAdded = false;
    }

    private void removeMv() {
        mVideoEditManager.setOverlayVideoFile(null, null);
        mIsOverlayVideoAdded = true;
    }

    // 涂鸦
    private void demoGraffitiPaint() {
        if (mPaintView == null) {
            addPaintView();
        } else {
            removePaintView();
        }
    }

    private void addPaintView() {
        mPaintView = new UPaintView(this, mRenderView.getWidth(), mRenderView.getHeight());
        mVideoEditManager.addPaintView(mPaintView);
    }

    private void removePaintView() {
        mVideoEditManager.removePaintView(mPaintView);
        mPaintView = null;
    }

    // 多段混音
    private void demoMultiAudioMix() {
        int clipDuration = 8;
        // 第一段音频
        UAudioMixClip firstAudioClip = new UAudioMixClip();
        firstAudioClip.path = "/sdcard/Download/kuaizi.mp3"; // 音频文件路径, 需要替换为您手机中的有效音频文件
        firstAudioClip.startMs = 30 * 1000; // 音频文件开始时间
        firstAudioClip.durationMs = clipDuration * 1000; // 混音音频时长
        firstAudioClip.startMsInVideo = 0 * 1000; // 视频文件中开始叠加位置
        // 第二段音频
        UAudioMixClip secondAudioClip = new UAudioMixClip();
        secondAudioClip.path = "/sdcard/Download/test.mp3";
        secondAudioClip.startMs = 60 * 1000;
        secondAudioClip.durationMs = clipDuration * 1000;
        secondAudioClip.startMsInVideo = clipDuration * 1000;
        // 第三段音频
        UAudioMixClip thirdClip = new UAudioMixClip();
        thirdClip.path = "/sdcard/Download/piano.mp3";
        thirdClip.startMs = 20 * 1000;
        thirdClip.durationMs = clipDuration * 1000;
        thirdClip.startMsInVideo = clipDuration * 2 * 1000;
        // 清除已添加音频
        mVideoEditManager.clearAudioClip();
        // 添加音频
        mVideoEditManager.addAudioClip(firstAudioClip);
        mVideoEditManager.addAudioClip(secondAudioClip);
        mVideoEditManager.addAudioClip(thirdClip);
        mVideoEditManager.setOriginVolume(0);
        mVideoEditManager.seekTo(0);
    }

    private void cancelSave() {
        mVideoEditManager.cancelSave();
    }

    private void saveVideo() {
        pausePlayback();
        mStartTime = System.currentTimeMillis();
        if (mVideoDuration > 30 * 1000) {
            UMediaTrimTime trimTime = new UMediaTrimTime(0, 30 * 1000);
            mVideoEditManager.setTrimTime(trimTime);
        }
        mVideoEditManager.setVideoSaveListener(this);
        mVideoEditManager.save(OUT_FILE);
    }

    @Override
    public void onVideoSaveProgress(float progress) {
        @SuppressLint("DefaultLocale")
        String msg = String.format("%.1f", progress * 100) + "%";
        runOnUiThread(() -> mTvTip.setText(msg));
    }

    @Override
    public void onVideoSaveSuccess(String path) {
        Log.i(TAG, "onVideoSaveSuccess: " + path);
        String msg = "耗时：" + (System.currentTimeMillis() - mStartTime) / 1000 + " 秒";
        runOnUiThread(() -> {
            mTvTip.setText(msg);
            mSaveButton.setText("保存");
            mSaveButton.setTag(null);
            startPlayback();
        });
    }

    @Override
    public void onVideoSaveCancel() {
        Log.i(TAG, "onVideoSaveCancel");
        runOnUiThread(() -> {
            mSaveButton.setText("保存");
            mSaveButton.setTag(null);
            mTvTip.setText("已取消");
        });
    }

}