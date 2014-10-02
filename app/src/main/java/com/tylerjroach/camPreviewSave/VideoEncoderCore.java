/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tylerjroach.camPreviewSave;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * This class wraps up the core components used for surface-input video encoding.
 * <p>
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 * <p>
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 */
public class VideoEncoderCore {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    // TODO: these ought to be configurable as well
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

    private Surface mInputSurface;
    private MediaMuxerWrapper mMuxerWrapper;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private MediaCodec.BufferInfo mAudioBufferInfo;

    private TrackInfo mVideoTrackInfo;
    private TrackInfo mAudioTrackInfo;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaFormat mVideoFormat;
    private MediaFormat mAudioFormat;
    private MediaFormat mVideoOutputFormat;
    private MediaFormat mAudioOutputFormat;

    // Audio
    public static final int SAMPLE_RATE = 44100;
    public static final int SAMPLES_PER_FRAME = 1024; // AAC
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private AudioRecord audioRecord;
    private long lastEncodedAudioTimeStamp = 0;


    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    public VideoEncoderCore(int width, int height, int bitRate, File outputFile)
            throws IOException {

        mVideoBufferInfo = new MediaCodec.BufferInfo();
        mVideoTrackInfo = new TrackInfo();

        mVideoFormat= MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        mVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        mVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        mVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + mVideoFormat);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mVideoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mVideoEncoder.configure(mVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mVideoEncoder.createInputSurface();
        mVideoEncoder.start();

        mAudioBufferInfo = new MediaCodec.BufferInfo();
        mAudioTrackInfo = new TrackInfo();

        mAudioFormat = new MediaFormat();
        mAudioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mAudioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
        mAudioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();


        mMuxerWrapper = new MediaMuxerWrapper(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mVideoTrackInfo.index = -1;
        mVideoTrackInfo.muxerWrapper = mMuxerWrapper;
        mAudioTrackInfo.index = -1;
        mAudioTrackInfo.muxerWrapper = mMuxerWrapper;
    }

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    public void stopRecording() {
        drainEncoder(true);
    }

    public void startRecording() {
        drainEncoder(false);
    }

    /**
     * Releases encoder resources.
     */
    public void release() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }

        mMuxerWrapper.stop();

    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    public void drainEncoder(boolean endOfStream) {

        TrackInfo trackInfo = mVideoTrackInfo;
        MediaCodec encoder = mVideoEncoder;
        final int TIMEOUT_USEC = 10000;

        //TODO: Get Muxer from trackInfo
        MediaMuxerWrapper muxerWrapper = trackInfo.muxerWrapper;

        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mVideoEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mVideoEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mVideoEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (muxerWrapper.started) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mVideoEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                trackInfo.index = mMuxerWrapper.addTrack(newFormat);

            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mVideoBufferInfo.size = 0;
                }

                if (mVideoBufferInfo.size != 0) {
                    if (!trackInfo.muxerWrapper.started) {
                        Log.e(TAG, "Muxer not started. dropping " + ((encoder == mVideoEncoder) ? " video" : " audio") + " frames");
                        //throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mVideoBufferInfo.offset);
                    encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);

                    muxerWrapper.muxer.writeSampleData(trackInfo.index, encodedData, mVideoBufferInfo);
                    if (VERBOSE) {
                        Log.d(TAG, "sent " + mVideoBufferInfo.size + " bytes to muxer, ts=" +
                                mVideoBufferInfo.presentationTimeUs);
                    }
                }

                mVideoEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        muxerWrapper.finishTrack();
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }

    class TrackInfo {
        int index = 0;
        MediaMuxerWrapper muxerWrapper;
    }

    class MediaMuxerWrapper {
        MediaMuxer muxer;
        final int TOTAL_NUM_TRACKS = 2;
        boolean started = false;
        int numTracksAdded = 0;
        int numTracksFinished = 0;

        Object sync = new Object();

        public MediaMuxerWrapper(File outputFile, int format){
            restart(outputFile, format);

        }

        public int addTrack(MediaFormat format){
            numTracksAdded++;
            int trackIndex = muxer.addTrack(format);
            if(numTracksAdded == TOTAL_NUM_TRACKS){
                if (VERBOSE) Log.i(TAG, "All tracks added, starting " + ((this == mMuxerWrapper) ? "muxer1" : "muxer2") +"!");
                muxer.start();
                started = true;
            }
            return trackIndex;
        }

        public void finishTrack(){
            numTracksFinished++;
            if(numTracksFinished == TOTAL_NUM_TRACKS){
                if (VERBOSE) Log.i(TAG, "All tracks finished, stopping " + ((this == mMuxerWrapper) ? "muxer1" : "muxer2") + "!");
                stop();
            }

        }

        public boolean allTracksAdded(){
            return (numTracksAdded == TOTAL_NUM_TRACKS);
        }

        public boolean allTracksFinished(){
            return (numTracksFinished == TOTAL_NUM_TRACKS);
        }


        public void stop(){
            if(muxer != null){
                if(!allTracksFinished()) Log.e(TAG, "Stopping Muxer before all tracks added!");
                if(!started) Log.e(TAG, "Stopping Muxer before it was started");
                muxer.stop();
                muxer.release();
                muxer = null;
                started = false;
                numTracksAdded = 0;
                numTracksFinished = 0;
            }
        }


        private void restart(File outputFile, int format){
            stop();
            try {
                muxer = new MediaMuxer(outputFile.toString(), format);
            } catch (IOException e) {
                throw new RuntimeException("MediaMuxer creation failed", e);
            }
        }
    }
}
