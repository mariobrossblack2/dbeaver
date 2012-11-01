/*
 * Copyright (C) 2010-2012 Serge Rieder
 * serge@jkiss.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.jkiss.dbeaver.model.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.utils.ContentUtils;

import java.io.*;
import java.util.Arrays;

/**
 * Memory content storage
 */
public class BytesContentStorage implements DBDContentStorage {

    static final Log log = LogFactory.getLog(BytesContentStorage.class);

    private byte[] data;
    private String encoding;

    public BytesContentStorage(byte[] data, String encoding)
    {
        this.data = data;
        this.encoding = encoding;
    }

    @Override
    public InputStream getContentStream()
        throws IOException
    {
        return new ByteArrayInputStream(data);
    }

    @Override
    public Reader getContentReader()
        throws IOException
    {
        return new InputStreamReader(
            getContentStream(),
            encoding);
    }

    @Override
    public long getContentLength()
    {
        return data.length;
    }

    @Override
    public String getCharset()
    {
        return null;
    }

    @Override
    public DBDContentStorage cloneStorage(DBRProgressMonitor monitor)
        throws IOException
    {
        return new BytesContentStorage(data, encoding);
    }

    @Override
    public void release()
    {
        data = null;
    }

    public static BytesContentStorage createFromStream(
        InputStream stream,
        long contentLength,
        String encoding)
        throws IOException
    {
        if (contentLength > Integer.MAX_VALUE) {
            throw new IOException("Too big content length for memory storage: " + contentLength);
        }
        byte[] data = new byte[(int)contentLength];
        int count = stream.read(data);
        if (count >= 0 && count != contentLength) {
            log.warn("Actual content length (" + count + ") is less than declared: " + contentLength);
            data = Arrays.copyOf(data, count);
        }
        return new BytesContentStorage(data, encoding);
    }
}
