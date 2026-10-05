package com.cadence.transcoder.application;

/** A permanent failure of one job (bad input, FFmpeg error or timeout): reported as track-transcode-failed. */
public class TranscodeException extends RuntimeException {

    public TranscodeException(String message) {
        super(message);
    }

    public TranscodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
