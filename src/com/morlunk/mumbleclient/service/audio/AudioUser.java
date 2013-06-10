/*
 * Copyright (C) 2013 Andrew Comminos
 *
 * Licensed under the
 *
 */

package com.morlunk.mumbleclient.service.audio;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.util.Log;

import com.morlunk.mumbleclient.Globals;
import com.morlunk.mumbleclient.jni.Native;
import com.morlunk.mumbleclient.jni.NativeAudio;
import com.morlunk.mumbleclient.service.MumbleProtocol;
import com.morlunk.mumbleclient.service.PacketDataStream;
import com.morlunk.mumbleclient.service.model.User;
import com.morlunk.mumbleclient.swig.speex.*;


public class AudioUser {

	public interface PacketReadyHandler {
		public void packetReady(AudioUser user);
	}
	
	private long mCeltMode;
	private long mCeltDecoder;
	private long mOpusDecoder;

    private final SWIGTYPE_p_JitterBuffer_ mJitterBuffer;
    private final Lock mJitterLock = new ReentrantLock(true);

    private final Queue<byte[]> mFrames = new ConcurrentLinkedQueue<byte[]>();
    private final Queue<byte[]> mAudioDataPool = new ConcurrentLinkedQueue<byte[]>();

    private final int mCodec;
    private int mFrameSize = MumbleProtocol.FRAME_SIZE;
    private int mAudioBufferSize;
    private int mConsumedSamples;
    private int mBufferFilled;
    private int mMissedFrames;
    private boolean mHasTerminator = false;
    private boolean mLastAlive = true;

    private float[] mBuffer = null;

	private final User mUser;

	public AudioUser(final User user, final int codec) {
		mUser = user;
		mCodec = codec;

		if(codec == MumbleProtocol.CODEC_ALPHA || codec == MumbleProtocol.CODEC_BETA) {
            mAudioBufferSize = mFrameSize;
			mCeltMode = Native.celt_mode_create(
					MumbleProtocol.SAMPLE_RATE,
					MumbleProtocol.FRAME_SIZE);
			mCeltDecoder = Native.celt_decoder_create(mCeltMode, 1);
		} else if(codec == MumbleProtocol.CODEC_OPUS) {
			// With opus, we have to make sure we can hold the largest frame size- 120ms, or 5760 samples.
            mAudioBufferSize = mFrameSize*12;
			mOpusDecoder = NativeAudio.opusDecoderCreate(MumbleProtocol.SAMPLE_RATE, 1);
		}

        mJitterBuffer = Speex.jitter_buffer_init(mFrameSize);
        int margin[] = new int[] { 10 * mFrameSize };
        SWIGTYPE_p_void marginPtr = Speex.intToVoidPointer(margin);

        Speex.jitter_buffer_ctl(mJitterBuffer, Speex.JITTER_BUFFER_SET_MARGIN, marginPtr);
	}

	public boolean addFrameToBuffer(
		final byte[] audioData,
        final long sequence,
		final PacketReadyHandler readyHandler) {
        if (audioData.length < 2)
            return false;

        mJitterLock.lock();

        PacketDataStream pds = new PacketDataStream(audioData);
        pds.next(); // Skip flags

        int dataSize;
		int samples = 0;

		if(mCodec == MumbleProtocol.CODEC_OPUS) {
			long header = pds.readLong();
			dataSize = (int) (header & ((1 << 13) - 1));
			if(dataSize > 0) {
				byte[] data = new byte[dataSize];
				pds.dataBlock(data, dataSize);
				int frames = NativeAudio.opusPacketGetFrames(data, dataSize);
				samples = frames * NativeAudio.opusPacketGetSamplesPerFrame(data, MumbleProtocol.SAMPLE_RATE);

				if(samples % MumbleProtocol.FRAME_SIZE != 0) {
					mJitterLock.unlock();
                    return false; // All samples must be divisible by the frame size.
                }
			} else {
                samples = mFrameSize; // Terminator packet
            }
		} else {
			int header = 0;
			do {
				header = pds.next();
                samples += mFrameSize;
                pds.skip(header & 0x7f);
			} while ((header & 0x80) > 0 && pds.isValid());
		}

        if(!pds.isValid()) {
            Log.e(Globals.LOG_TAG, "Invalid packet data stream used when adding frame to buffer!");
            mJitterLock.unlock();
            return false;
        }

        byte[] jitterAudioData = createAudioDataArray();
        System.arraycopy(audioData, 0, jitterAudioData, 0, audioData.length);

        final JitterBufferPacket jbp = new JitterBufferPacket();
        jbp.setData(jitterAudioData);
        jbp.setLen(jitterAudioData.length);
        jbp.setSpan(samples);
        jbp.setTimestamp(sequence * mFrameSize);

        //Log.v(Globals.LOG_TAG, "Packet received.\nLength: "+jbp.len+"\nSamples: "+jbp.span+"\nTimestamp: "+jbp.timestamp+"\nPacket: "+Arrays.toString(jbp.data));

        Speex.jitter_buffer_put(mJitterBuffer, jbp);

        Log.v(Globals.LOG_TAG, "Packet put.");

        readyHandler.packetReady(this);

        freeAudioDataArray(jitterAudioData);

        mJitterLock.unlock();
		
		return true;
	}

	public boolean needSamples(int bufferSize) {
        for(int i = mConsumedSamples; i < mBufferFilled;i++) {
            mBuffer[i-mConsumedSamples] = mBuffer[i]; // Shift samples left after consumption of buffer
        }

        mBufferFilled -= mConsumedSamples;

        mConsumedSamples = bufferSize;

        if(mBufferFilled >= bufferSize)
            return mLastAlive;

        boolean nextAlive = mLastAlive;
        float[] output = new float[mAudioBufferSize];

        while(mBufferFilled < bufferSize) {
            int decodedSamples = mFrameSize;
            resizeBuffer(mBufferFilled + mAudioBufferSize);

            Log.i(Globals.LOG_TAG, "Audio buffer: "+mAudioBufferSize+" buffer size: "+mBuffer.length+" filled: "+mBufferFilled);

            // Shift buffer to current frame
            System.arraycopy(mBuffer, mBufferFilled, output, 0, mAudioBufferSize);

            if(!mLastAlive) {
                // If this is a new frame, clear the buffer
                Arrays.fill(mBuffer, 0);
            } else {

                int avail = 0;

                mJitterLock.lock();
                int ts[] = new int[] { Speex.jitter_buffer_get_pointer_timestamp(mJitterBuffer) };
                SWIGTYPE_p_void tsPtr = Speex.intToVoidPointer(ts);
                Speex.jitter_buffer_ctl(mJitterBuffer, Speex.JITTER_BUFFER_GET_AVAILABLE_COUNT, tsPtr);
                mJitterLock.unlock();

                if(ts[0] == 0) {
                    if(mMissedFrames < 20) {
                        Arrays.fill(output, 0);
                        mBufferFilled += decodedSamples;
                        continue;
                    }
                }

                if(mFrames.size() == 0) {
                    mJitterLock.lock();
                    byte[] data = new byte[4096];

                    JitterBufferPacket jbp = new JitterBufferPacket();
                    jbp.setData(data);
                    jbp.setLen(4096);

                    int startOffset[] = new int[] { 0 };

                    if(Speex.jitter_buffer_get(mJitterBuffer, jbp, mFrameSize, startOffset) == Speex.JITTER_BUFFER_OK) {
                        PacketDataStream pds = new PacketDataStream(jbp.getData());

                        mMissedFrames = 0;
                        pds.next(); // Skip flags
                        mHasTerminator = false;

                        if(mCodec == MumbleProtocol.UDPMESSAGETYPE_UDPVOICEOPUS) {
                            long header = pds.readLong();
                            int size = (int) (header & ((1 << 13) - 1));
                            mHasTerminator = (header & (1 << 13)) == 1;
                            if(size > 0) {
                                byte[] frameData = new byte[size];
                                pds.dataBlock(frameData, size);
                                mFrames.add(frameData);
                            }
                        } else {
                            int header = 0;
                            do {
                                header = pds.next();
                                if(header != 0) {
                                    int size = header & 0x7f;
                                    byte[] frameData = new byte[size];
                                    pds.dataBlock(frameData, size);
                                    mFrames.add(frameData);
                                } else {
                                    mHasTerminator = true;
                                }
                            } while((header & 0x80) == 1 && pds.isValid());
                        }
                    } else {
                        Speex.jitter_buffer_update_delay(mJitterBuffer, jbp, startOffset);

                        mMissedFrames++;
                        if(mMissedFrames > 10)
                            nextAlive = false;
                    }
                    mJitterLock.unlock();
                }

                if(mFrames.size() > 0) {
                    byte[] frameData = mFrames.poll();

                    Log.i(Globals.LOG_TAG, "Frame data from JBP: "+Arrays.toString(frameData));

                    if(mCodec == MumbleProtocol.UDPMESSAGETYPE_UDPVOICEOPUS)  {
                        decodedSamples = NativeAudio.opusDecodeFloat(mOpusDecoder, frameData, frameData.length, output, mAudioBufferSize, 0);
                    } else {
                        if(frameData.length != 0)
                            Native.celt_decode_float(mCeltDecoder, frameData, frameData.length, output);
                    }
                } else {
                    if(mCodec == MumbleProtocol.UDPMESSAGETYPE_UDPVOICEOPUS)  {
                        decodedSamples = NativeAudio.opusDecodeFloat(mOpusDecoder, null, 0, output, mFrameSize, 0);
                    } else {
                        Native.celt_decode_float(mCeltDecoder, null, 0, output);
                    }
                }

                Log.d(Globals.LOG_TAG, "Decoded: " + decodedSamples);

                mJitterLock.lock();
                for(int i=0; i < decodedSamples/mFrameSize; i++) {
                    Speex.jitter_buffer_tick(mJitterBuffer); // Tick for each sample decoded
                }
                mJitterLock.unlock();
            }

            mBufferFilled += decodedSamples;
        }

        boolean lastAlive = mLastAlive;
        mLastAlive = nextAlive;
        return lastAlive;
	}

    private byte[] createAudioDataArray() {
        byte[] audioData = mAudioDataPool.poll();

        if(audioData == null)
            audioData = new byte[128];

        return audioData;
    }

    private void freeAudioDataArray(final byte[] audioData) {
        mAudioDataPool.add(audioData);
    }

    /**
     * Resizes the buffer to the new size specified.
     * Will create the buffer if necessary.
     * @param newSize The size to set the buffer to (in samples).
     */
    private void resizeBuffer(int newSize) {
        float[] newBuffer = new float[newSize];
        if(mBuffer != null)
            System.arraycopy(mBuffer, 0, newBuffer, 0, mBuffer.length);
        mBuffer = newBuffer;
    }
	
	boolean isStreaming() {
		if(mCodec == MumbleProtocol.CODEC_ALPHA || mCodec == MumbleProtocol.CODEC_BETA) {
			return mMissedFrames < 10;
		} else if(mCodec == MumbleProtocol.CODEC_OPUS) {
			// Make sure our buffer isn't entirely missed frames. (buffer holds 12 480 sample frames max)
			return mMissedFrames < (mAudioBufferSize/mFrameSize);
		}
		return false;
	}

	@Override
	protected final void finalize() {
		if(mCodec == MumbleProtocol.CODEC_ALPHA || mCodec == MumbleProtocol.CODEC_BETA) {
			Native.celt_decoder_destroy(mCeltDecoder);
			Native.celt_mode_destroy(mCeltMode);
		} else if(mCodec == MumbleProtocol.CODEC_OPUS) {
			NativeAudio.opusDecoderDestroy(mOpusDecoder);
		}
        Speex.jitter_buffer_destroy(mJitterBuffer);
	}

    public User getUser() {
        return mUser;
    }

    public float[] getBuffer() {
        return mBuffer;
    }

    public int getCodec() {
        return mCodec;
    }
}
