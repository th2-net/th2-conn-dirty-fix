/*******************************************************************************
 * Copyright (c) 2023-2025, Exactpro Systems LLC
 * www.exactpro.com
 * Build Software to Test Software
 *
 * All rights reserved.
 * This is unpublished, licensed software, confidential and proprietary
 * information which is the property of Exactpro Systems LLC or its licensors.
 ******************************************************************************/
import io.grpc.Context

class ProviderCall {
    companion object {
        fun <T> withCancellation(code: () -> T): T {
            return Context.current().withCancellation().use { context ->
                val toRestore = context.attach()
                val result = try {
                    code()
                } finally {
                    context.detach(toRestore)
                }
                return@use result
            }
        }
    }
}