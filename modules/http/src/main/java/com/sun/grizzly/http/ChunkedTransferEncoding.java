/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package com.sun.grizzly.http;

import com.sun.grizzly.Buffer;
import com.sun.grizzly.Connection;
import com.sun.grizzly.http.HttpCodecFilter.ContentParsingState;
import com.sun.grizzly.http.HttpCodecFilter.ParsingState;
import com.sun.grizzly.http.util.Ascii;
import com.sun.grizzly.http.util.HexUtils;
import com.sun.grizzly.http.util.MimeHeaders;
import com.sun.grizzly.memory.BufferUtils;
import com.sun.grizzly.memory.MemoryManager;

/**
 * Chunked transfer encoding implementation.
 *
 * @see TransferEncoding
 * 
 * @author Alexey Stashok
 */
public final class ChunkedTransferEncoding implements TransferEncoding {
    private static final int MAX_HTTP_CHUNK_SIZE_LENGTH = 16;
    
    private final int maxHeadersSize;

    public ChunkedTransferEncoding(int maxHeadersSize) {
        this.maxHeadersSize = maxHeadersSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean wantDecode(HttpHeader httpPacket) {
        return httpPacket.isChunked();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean wantEncode(HttpHeader httpPacket) {
        return httpPacket.isChunked();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepareSerialize(HttpHeader httpHeader, HttpContent content) {
        httpHeader.makeTransferEncodingHeader(Constants.CHUNKED_ENCODING);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings({"UnusedDeclaration"})
    public ParsingResult parsePacket(Connection connection,
            HttpHeader httpPacket, Buffer input) {
        final HttpPacketParsing httpPacketParsing = (HttpPacketParsing) httpPacket;

        // Get HTTP content parsing state
        final ContentParsingState contentParsingState =
                httpPacketParsing.getContentParsingState();

        // if it's chunked HTTP message
        boolean isLastChunk = contentParsingState.isLastChunk;
        // Check if HTTP chunk length was parsed
        if (!isLastChunk && contentParsingState.chunkRemainder <= 0) {
            // We expect next chunk header
            if (!parseHttpChunkLength(httpPacketParsing, input)) {
                // if we don't have enough data to parse chunk length - stop execution
                return ParsingResult.create(null, input);
            }
        } else {
            // HTTP content starts from position 0 in the input Buffer (HTTP chunk header is not part of the input Buffer)
            contentParsingState.chunkContentStart = 0;
        }

        // Get the position in the input Buffer, where actual HTTP content starts
        int chunkContentStart =
                contentParsingState.chunkContentStart;

        if (contentParsingState.chunkLength == 0) {
            // if it's the last HTTP chunk
            if (!isLastChunk) {
                // set it's the last chunk
                contentParsingState.isLastChunk = true;
                isLastChunk = true;
                // start trailer parsing
                initTrailerParsing(httpPacketParsing);
            }

            // Check if trailer is present
            if (!parseLastChunkTrailer(httpPacketParsing, input)) {
                // if yes - and there is not enough input data - stop the
                // filterchain processing
                return ParsingResult.create(null, input);
            }

            // move the content start position after trailer parsing
            chunkContentStart = httpPacketParsing.getHeaderParsingState().offset;
        }

        // Get the number of bytes remaining in the current chunk
        final long thisPacketRemaining =
                contentParsingState.chunkRemainder;
        // Get the number of content bytes available in the current input Buffer
        final int contentAvailable = input.limit() - chunkContentStart;

        Buffer remainder = null;
        if (contentAvailable > thisPacketRemaining) {
            // If input Buffer has part of the next message - slice it
            remainder = input.slice(
                    (int) (chunkContentStart + thisPacketRemaining),
                    input.limit());
            input.position(chunkContentStart);
            input.limit((int) (chunkContentStart + thisPacketRemaining));
        } else if (chunkContentStart > 0) {
            input.position(chunkContentStart);
        }

        // recalc the HTTP chunk remaining content
        contentParsingState.chunkRemainder -= (input.limit() - chunkContentStart);

        if (isLastChunk) {
            // Build last chunk content message
            return ParsingResult.create(httpPacket.httpTrailerBuilder().
                    headers(contentParsingState.trailerHeaders).build(), remainder);

        }

        return ParsingResult.create(httpPacket.httpContentBuilder().content(input).build(), remainder);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Buffer serializePacket(Connection connection, HttpContent httpContent) {
        final MemoryManager memoryManager = connection.getTransport().getMemoryManager();
        return encodeHttpChunk(memoryManager,
                httpContent, httpContent.isLast());
    }

    private void initTrailerParsing(HttpPacketParsing httpPacket) {
        final ParsingState headerParsingState =
                httpPacket.getHeaderParsingState();
        final ContentParsingState contentParsingState =
                httpPacket.getContentParsingState();

        headerParsingState.subState = 0;
        final int start = contentParsingState.chunkContentStart;
        headerParsingState.start = start;
        headerParsingState.offset = start;
        headerParsingState.packetLimit = start + maxHeadersSize;
    }

    private boolean parseLastChunkTrailer(HttpPacketParsing httpPacket,
            Buffer input) {
        final ParsingState headerParsingState =
                httpPacket.getHeaderParsingState();
        final ContentParsingState contentParsingState =
                httpPacket.getContentParsingState();

        return HttpCodecFilter.parseHeaders(null, contentParsingState.trailerHeaders,
                headerParsingState, input);
    }

    private boolean parseHttpChunkLength(HttpPacketParsing httpPacket,
            Buffer input) {
        final ParsingState parsingState = httpPacket.getHeaderParsingState();

        while (true) {
            _1:
            switch (parsingState.state) {
                case 0: {// Initialize chunk parsing
                    final int pos = input.position();
                    parsingState.start = pos;
                    parsingState.offset = pos;
                    parsingState.packetLimit = pos + MAX_HTTP_CHUNK_SIZE_LENGTH;
                    parsingState.state = 1;
                }

                case 1: { // Scan chunk size
                    int offset = parsingState.offset;
                    int limit = Math.min(parsingState.packetLimit, input.limit());
                    long value = parsingState.parsingNumericValue;

                    while (offset < limit) {
                        final byte b = input.get(offset);
                        if (b == Constants.CR || b == Constants.SEMI_COLON) {
                            parsingState.checkpoint = offset;
                        } else if (b == Constants.LF) {
                            final ContentParsingState contentParsingState =
                                    httpPacket.getContentParsingState();
                            contentParsingState.chunkContentStart = offset + 1;
                            contentParsingState.chunkLength = value;
                            contentParsingState.chunkRemainder = value;
                            parsingState.state = 2;

                            return true;
                        } else if (parsingState.checkpoint == -1) {
                            value = value * 16 + (HexUtils.DEC[b]);
                        } else {
                            throw new IllegalStateException("Unexpected HTTP chunk header");
                        }

                        offset++;
                    }

                    parsingState.parsingNumericValue = value;
                    parsingState.offset = offset;
                    parsingState.checkOverflow();
                    return false;

                }

                case 2: { // skip CRLF
                    while (input.hasRemaining()) {
                        if (input.get() == Constants.LF) {
                            parsingState.recycle();
                            if (input.hasRemaining()) {
                                break _1;
                            }

                            return false;
                        }
                    }

                    return false;
                }
            }
        }
    }

    private Buffer encodeHttpChunk(MemoryManager memoryManager,
            HttpContent httpContent, boolean isLastChunk) {
        final Buffer content = httpContent.getContent();

        Buffer httpChunkBuffer = memoryManager.allocate(16);
        final int chunkSize = content.remaining();

        Ascii.intToHexString(httpChunkBuffer, chunkSize);
        httpChunkBuffer = HttpCodecFilter.put(memoryManager, httpChunkBuffer,
                Constants.CRLF_BYTES);
        httpChunkBuffer.trim();
        httpChunkBuffer.allowBufferDispose(true);

        final boolean hasContent = chunkSize > 0;

        if (hasContent) {
            httpChunkBuffer = BufferUtils.appendBuffers(memoryManager,
                    httpChunkBuffer, content);
        }

        Buffer httpChunkTrailer = memoryManager.allocate(256);

        if (!isLastChunk) {
            httpChunkTrailer = HttpCodecFilter.put(memoryManager, httpChunkTrailer,
                    Constants.CRLF_BYTES);
        } else {
            if (hasContent) {
                httpChunkTrailer = HttpCodecFilter.put(memoryManager, httpChunkTrailer,
                        Constants.CRLF_BYTES);
                httpChunkTrailer = HttpCodecFilter.put(memoryManager, httpChunkTrailer,
                        Constants.LAST_CHUNK_CRLF_BYTES);
            }

            if (httpContent instanceof HttpTrailer) {
                final HttpTrailer httpTrailer = (HttpTrailer) httpContent;
                final MimeHeaders mimeHeaders = httpTrailer.getHeaders();
                httpChunkTrailer = HttpCodecFilter.encodeMimeHeaders(memoryManager,
                        httpChunkTrailer, mimeHeaders);
            }

            httpChunkTrailer = HttpCodecFilter.put(memoryManager, httpChunkTrailer,
                    Constants.CRLF_BYTES);
        }

        httpChunkTrailer.trim();
        httpChunkTrailer.allowBufferDispose(true);

        return BufferUtils.appendBuffers(memoryManager, httpChunkBuffer,
                httpChunkTrailer);
    }
}
