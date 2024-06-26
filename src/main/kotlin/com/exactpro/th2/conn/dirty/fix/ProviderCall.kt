/*******************************************************************************
 * Copyright (c) 2023, Exactpro Systems LLC
 * www.exactpro.com
 * Build Software to Test Software
 *
 * All rights reserved.
 * This is unpublished, licensed software, confidential and proprietary
 * information which is the property of Exactpro Systems LLC or its licensors.
 ******************************************************************************/
package com.exactpro.th2.conn.dirty.fix

import io.grpc.Context

class ProviderCall {
    companion object {
        fun <T> withCancellation(code: () -> T): T {
            return Context.current().withCancellation().use { context ->
                context.call { code() }
            }
        }
    }
}