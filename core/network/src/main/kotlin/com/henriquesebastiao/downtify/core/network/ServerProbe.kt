package com.henriquesebastiao.downtify.core.network

import com.henriquesebastiao.downtify.core.model.ServerAddress
import com.henriquesebastiao.downtify.core.model.ServerInfo
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/** Checks an address with `GET /api/server/info` before the app pairs with it. */
class ServerProbe @Inject constructor(private val apis: ApiFactory) {
    sealed interface Result {
        data class Found(val baseUrl: String, val info: ServerInfo) : Result
        data object InvalidAddress : Result
        data object Unreachable : Result

        /** Something answered, but it isn't a Downtify server. */
        data class NotDowntify(val reason: String) : Result

        /** A Downtify whose API is newer than this app understands: update the app. */
        data class TooNew(val info: ServerInfo) : Result
    }

    /** Tries each candidate for [input] and returns the first that answers. */
    suspend fun probe(input: String): Result {
        val candidates = ServerAddress.candidates(input)
        if (candidates.isEmpty()) return Result.InvalidAddress
        var best: Result = Result.Unreachable
        for (baseUrl in candidates) {
            when (val result = check(baseUrl)) {
                is Result.Found, is Result.TooNew -> return result
                is Result.NotDowntify -> best = result
                else -> Unit
            }
        }
        return best
    }

    /** Checks exactly [baseUrl]. */
    @Suppress("TooGenericExceptionCaught") // Any failure to reach or parse means "not usable".
    suspend fun check(baseUrl: String): Result {
        val info = try {
            apis.create(baseUrl).serverInfo().toModel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            return Result.NotDowntify("JSON: ${e.message?.take(180) ?: \"falha ao interpretar resposta\"}")
        } catch (e: Exception) {
            return if (e.httpStatus != null) Result.NotDowntify("HTTP ${e.httpStatus}") else Result.NotDowntify("Rede: ${e.javaClass.simpleName}: ${e.message?.take(160).orEmpty()}")
        }
        return when (info.compatibility) {
            ServerInfo.Compatibility.Ok -> Result.Found(baseUrl, info)
            ServerInfo.Compatibility.NotDowntify -> Result.NotDowntify("Produto recebido: ${info.product.take(80)}; API: ${info.apiVersion}")
            ServerInfo.Compatibility.TooNew -> Result.TooNew(info)
        }
    }
}
