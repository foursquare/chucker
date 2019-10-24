package com.chuckerteam.chucker.api

import android.content.Context
import android.util.Log
import com.chuckerteam.chucker.api.Chucker.LOG_TAG
import com.chuckerteam.chucker.api.config.HttpFeature
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.support.FeatureManager
import com.chuckerteam.chucker.internal.support.IOUtils
import com.chuckerteam.chucker.internal.support.hasBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.UnsupportedCharsetException
import java.util.concurrent.TimeUnit

private const val MAX_BLOB_SIZE = 1000_000L

/**
 * An OkHttp Interceptor which persists and displays HTTP activity
 * in your application for later inspection.
 *
 * @param context An Android [Context]
 * @param collector A [ChuckerCollector] to customize data retention
 */
class ChuckerInterceptor @JvmOverloads constructor(
    private val context: Context,
    private val collector: ChuckerCollector = ChuckerCollector(context)
) : Interceptor {

    private val io: IOUtils = IOUtils(context)
    private val httpFeature: HttpFeature = FeatureManager.find()

    fun redactHeader(name: String) = apply {
        httpFeature.headersToRedact.add(name)
    }

    @Throws(IOException::class)
    @Suppress("LongMethod", "ComplexMethod")
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return if (httpFeature.enabled) {
            captureTransaction(request, chain)
        } else {
            chain.proceed(request)
        }
    }

    private fun captureTransaction(request: Request, chain: Interceptor.Chain): Response {
        val transaction = captureRequest(request)
        collector.onRequestSent(transaction)

        val startNs = System.nanoTime()
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            transaction.error = e.toString()
            collector.onResponseReceived(transaction)
            throw e
        }

        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        val responseBody = response.body()

        // includes headers added later in the chain
        transaction.setRequestHeaders(filterHeaders(response.request().headers()))
        transaction.apply {
            responseDate = System.currentTimeMillis()
            this.tookMs = tookMs
            protocol = response.protocol().toString()
            responseCode = response.code()
            responseMessage = response.message()

            responseContentType = responseBody?.contentType()?.toString()
            responseContentLength = responseBody?.contentLength() ?: 0L
            setResponseHeaders(filterHeaders(response.headers()))
        }

        val responseEncodingIsSupported = io.bodyHasSupportedEncoding(response.headers().get("Content-Encoding"))
        transaction.isResponseBodyPlainText = responseEncodingIsSupported

        if (response.hasBody() && responseEncodingIsSupported) {
            val source = getNativeSource(response)
            source.request(java.lang.Long.MAX_VALUE)
            val buffer = source.buffer()
            var charset: Charset = UTF8
            val contentType = responseBody?.contentType()
            if (contentType != null) {
                try {
                    charset = contentType.charset(UTF8) ?: UTF8
                } catch (e: UnsupportedCharsetException) {
                    collector.onResponseReceived(transaction)
                    return response
                }
            }
            transaction.completeWithBody(buffer, charset)
        }

        collector.onResponseReceived(transaction)

        return response
    }

    private fun captureRequest(request: Request): HttpTransaction {
        val requestBody = request.body()
        val transaction = HttpTransaction()

        transaction.apply {
            requestDate = System.currentTimeMillis()
            method = request.method()
            populateUrl(request.url().toString())
            setRequestHeaders(request.headers())
            requestContentType = requestBody?.contentType()?.toString()
            requestContentLength = requestBody?.contentLength() ?: 0L
        }

        val encodingIsSupported = io.bodyHasSupportedEncoding(request.headers().get("Content-Encoding"))
        transaction.isRequestBodyPlainText = encodingIsSupported

        if (requestBody != null && encodingIsSupported) {
            val source = io.getNativeSource(Buffer(), io.bodyIsGzipped(request.headers().get("Content-Encoding")))
            val buffer = source.buffer()
            requestBody.writeTo(buffer)
            var charset: Charset = UTF8
            val contentType = requestBody.contentType()
            if (contentType != null) {
                charset = contentType.charset(UTF8) ?: UTF8
            }
            if (io.isPlaintext(buffer)) {
                val content = io.readFromBuffer(buffer, charset, httpFeature.maxContentLength)
                transaction.requestBody = content
            } else {
                transaction.isResponseBodyPlainText = false
            }
        }
        return transaction
    }

    /** Overrides all the headers in [HttpFeature.headersToRedact] with a `**` */
    private fun filterHeaders(headers: Headers): Headers {
        val builder = headers.newBuilder()
        for (name in headers.names()) {
            if (name in httpFeature.headersToRedact) {
                builder.set(name, "**")
            }
        }
        return builder.build()
    }

    /**
     * Returns the [BufferedSource] of the response and also UnGzip it if necessary.
     */
    @Throws(IOException::class)
    private fun getNativeSource(response: Response): BufferedSource {
        if (io.bodyIsGzipped(response.headers().get("Content-Encoding"))) {
            val source = response.peekBody(httpFeature.maxContentLength).source()
            if (source.buffer().size() < httpFeature.maxContentLength) {
                return io.getNativeSource(source, true)
            } else {
                Log.w(LOG_TAG, "gzip encoded response was too long")
            }
        }
        return response.body()!!.source()
    }

    private fun HttpTransaction.completeWithBody(buffer: Buffer, charset: Charset) {
        if (io.isPlaintext(buffer)) {
            val content = io.readFromBuffer(buffer.clone(), charset, httpFeature.maxContentLength)
            this.responseBody = content
        } else {
            this.isResponseBodyPlainText = false

            if (this.responseContentType?.contains("image") == true && buffer.size() < MAX_BLOB_SIZE) {
                this.responseImageData = buffer.clone().readByteArray()
            }
        }
        this.responseContentLength = buffer.size()
    }

    companion object {
        private val UTF8 = Charset.forName("UTF-8")
    }
}
