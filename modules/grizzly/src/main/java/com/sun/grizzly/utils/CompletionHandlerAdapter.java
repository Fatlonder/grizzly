/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2010 Sun Microsystems, Inc. All rights reserved.
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

package com.sun.grizzly.utils;

import com.sun.grizzly.CompletionHandler;
import com.sun.grizzly.impl.FutureImpl;

/**
 *
 * @author Alexey Stashok
 */
public class CompletionHandlerAdapter<A, B>
        implements CompletionHandler<B> {

    private final static ResultAdapter DIRECT_ADAPTER = new ResultAdapter() {
        @Override
        public Object adapt(Object result) {
            return result;
        }
    };

    private final ResultAdapter<A, B> adapter;
    private final FutureImpl<A> future;
    private final CompletionHandler<A> completionHandler;

    public CompletionHandlerAdapter(FutureImpl<A> future) {
        this(future, null);
    }

    public CompletionHandlerAdapter(FutureImpl<A> future,
            CompletionHandler<A> completionHandler) {
        this(future, completionHandler, null);
    }

    public CompletionHandlerAdapter(FutureImpl<A> future,
            CompletionHandler<A> completionHandler,
            ResultAdapter<A, B> adapter) {
        this.future = future;
        this.completionHandler = completionHandler;
        if (adapter != null) {
            this.adapter = adapter;
        } else {
            this.adapter = DIRECT_ADAPTER;
        }
    }


    @Override
    public void cancelled() {
        future.cancel(false);
        if (completionHandler != null) {
            completionHandler.cancelled();
        }
    }

    @Override
    public void failed(Throwable throwable) {
        future.failure(throwable);
        if (completionHandler != null) {
            completionHandler.failed(throwable);
        }
    }

    @Override
    public void completed(B result) {
        final A adaptedResult = adapt(result);
        
        future.result(adaptedResult);
        if (completionHandler != null) {
            completionHandler.completed(adaptedResult);
        }
    }

    @Override
    public void updated(B result) {
        final A adaptedResult = adapter.adapt(result);

        if (completionHandler != null) {
            completionHandler.updated(adaptedResult);
        }
    }

    protected A adapt(B result) {
        return adapter.adapt(result);
    }
    
    public interface ResultAdapter<K, V> {
        public K adapt(V result);
    }
}
