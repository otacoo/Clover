/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.otacoo.chan.core.exception;

import org.otacoo.chan.R;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

public class ChanLoaderException extends Exception {
    private int statusCode = -1;

    public ChanLoaderException(String message) {
        super(message);
    }

    public ChanLoaderException(String message, Throwable cause) {
        super(message, cause);
    }

    public ChanLoaderException(Throwable cause) {
        super(cause);
    }

    public ChanLoaderException(int statusCode) {
        this.statusCode = statusCode;
    }

    public ChanLoaderException() {
    }

    public Throwable getThrowable() {
        return getCause() != null ? getCause() : this;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }

    public int getErrorMessage() {
        Throwable cause = getCause();
        if (cause instanceof SSLException) {
            return R.string.thread_load_failed_ssl;
        } else if (cause instanceof SocketTimeoutException ||
                cause instanceof UnknownHostException ||
                cause instanceof IOException) {
            return R.string.thread_load_failed_network;
        } else if (statusCode != -1) {
            if (statusCode == 404) {
                return R.string.thread_load_failed_not_found;
            } else {
                return R.string.thread_load_failed_server;
            }
        } else {
            return R.string.thread_load_failed_parsing;
        }
    }
}
