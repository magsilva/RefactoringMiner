/*
    Android Asynchronous Http Client
    Copyright (c) 2013 Jason Choy <jjwchoy@gmail.com>
    http://loopj.com

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.loopj.android.http;

import android.os.Looper;

import java.lang.ref.WeakReference;

/**
 * A Handle to an AsyncRequest which can be used to cancel a running request.
 */
public class RequestHandle {
    private final WeakReference<AsyncHttpRequest> request;
    private WeakReference<Object> TAG = new WeakReference<Object>(null);

    public RequestHandle(AsyncHttpRequest request) {
        this.request = new WeakReference<AsyncHttpRequest>(request);
    }

    /**
     * Attempts to cancel this request. This attempt will fail if the request has already completed,
     * has already been cancelled, or could not be cancelled for some other reason. If successful,
     * and this request has not started when cancel is called, this request should never run. If the
     * request has already started, then the mayInterruptIfRunning parameter determines whether the
     * thread executing this request should be interrupted in an attempt to stop the request.
     * <p>&nbsp;</p> After this method returns, subsequent calls to isDone() will always return
     * true. Subsequent calls to isCancelled() will always return true if this method returned
     * true. Subsequent calls to isDone() will return true either if the request got cancelled by
     * this method, or if the request completed normally
     *
     * @param mayInterruptIfRunning true if the thread executing this request should be interrupted;
     *                              otherwise, in-progress requests are allowed to complete
     * @return false if the request could not be cancelled, typically because it has already
     * completed normally; true otherwise
     */
    public boolean cancel(final boolean mayInterruptIfRunning) {
        final AsyncHttpRequest _request = request.get();
        if (_request != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        _request.cancel(mayInterruptIfRunning);
                    }
                }).start();
                // Cannot reliably tell if the request got immediately canceled at this point
                // we'll assume it got cancelled
                return true;
            } else {
                return _request.cancel(mayInterruptIfRunning);
            }
        }
        return false;
    }

    /**
     * Returns true if this task completed. Completion may be due to normal termination, an
     * exception, or cancellation -- in all of these cases, this method will return true.
     *
     * @return true if this task completed
     */
    public boolean isFinished() {
        AsyncHttpRequest _request = request.get();
        return _request == null || _request.isDone();
    }

    /**
     * Returns true if this task was cancelled before it completed normally.
     *
     * @return true if this task was cancelled before it completed
     */
    public boolean isCancelled() {
        AsyncHttpRequest _request = request.get();
        return _request == null || _request.isCancelled();
    }

    public boolean shouldBeGarbageCollected() {
        boolean should = isCancelled() || isFinished();
        if (should)
            request.clear();
        return should;
    }

    /**
     * Will set Object as TAG to this request, wrapped by WeakReference
     *
     * @param tag Object used as TAG to this RequestHandle
     * @return this RequestHandle to allow fluid syntax
     */
    public RequestHandle setTag(Object tag) {
        TAG = new WeakReference<Object>(tag);
        return this;
    }

    /**
     * Will return TAG of this RequestHandle
     *
     * @return Object TAG, can be null
     */
    public Object getTag() {
        return TAG.get();
    }
}