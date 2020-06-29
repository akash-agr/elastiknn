package com.klibisz.elastiknn

import java.{lang, util}
import java.time.Duration
import java.util.concurrent.{Callable, TimeUnit}

import com.google.common.cache.{Cache, CacheBuilder, Weigher}
import com.klibisz.elastiknn.storage.StoredVec
import org.apache.logging.log4j.LogManager
import org.apache.lucene.index.{BinaryDocValues, NumericDocValues}
import org.apache.lucene.util.BytesRef
import org.elasticsearch.common.settings.{ClusterSettings, Setting, Settings}
import org.elasticsearch.common.settings.Setting.Property.{Final, NodeScope}

object VecCache {

  private val logger = LogManager.getLogger(this.getClass)

  // Elasticsearch cache settings. Read at startup and fixed.
  val NAME_CACHE_ENABLED = "elastiknn.cache.enabled"
  val NAME_CACHE_CAPACITY_BYTES = "elastiknn.cache.capacity_bytes"
  val NAME_CACHE_TTL_SECONDS = "elastiknn.cache.ttl_seconds"

  val cacheEnabled: Setting[lang.Boolean] = Setting.boolSetting(NAME_CACHE_ENABLED, true, NodeScope, Final)
  val cacheCapacityBytes: Setting[lang.Long] = Setting.longSetting(NAME_CACHE_CAPACITY_BYTES, 1e6.toLong, 0, NodeScope, Final)
  val cacheTTLSeconds: Setting[lang.Long] = Setting.longSetting(NAME_CACHE_TTL_SECONDS, 600, 0, NodeScope, Final)

  logger.info(s"Settings: ${Seq(cacheEnabled, cacheCapacityBytes, cacheTTLSeconds)}")

  final case class Key(docId: Int, bytesRefCacheKey: Long)

  private var _sparseBool: Option[Cache[Key, StoredVec.SparseBool]] = None
  private var _denseFloat: Option[Cache[Key, StoredVec.DenseFloat]] = None

  def initializeForSettings(settings: Settings): Unit = {
    val enabled = cacheEnabled.get(settings)
    if (!enabled) {
      logger.info(s"Not initializing cache because it's disabled in the settings.")
    } else {
      val cacheCapacityBytes = this.cacheCapacityBytes.get(settings)
      val cacheTTLSeconds = this.cacheTTLSeconds.get(settings)
      logger.info(s"Initializing cache with max capacity ${cacheCapacityBytes} bytes and TTL ${cacheTTLSeconds} seconds")
      _sparseBool = Some(
        CacheBuilder.newBuilder
          .concurrencyLevel(Runtime.getRuntime.availableProcessors())
          .maximumWeight(cacheCapacityBytes)
          .weigher((key: Key, value: StoredVec.SparseBool) => value.trueIndices.length * 4)
          .expireAfterWrite(cacheTTLSeconds, TimeUnit.SECONDS)
          .build()
      )
      _denseFloat = Some(
        CacheBuilder.newBuilder
          .concurrencyLevel(Runtime.getRuntime.availableProcessors())
          .maximumWeight(cacheCapacityBytes)
          .weigher((key: Key, value: StoredVec.DenseFloat) => value.values.length * 4)
          .expireAfterWrite(cacheTTLSeconds, TimeUnit.SECONDS)
          .build()
      )
    }
  }

  def sparseBoolCache(): Option[Cache[Key, StoredVec.SparseBool]] = _sparseBool
  def denseFloatCache(): Option[Cache[Key, StoredVec.DenseFloat]] = _denseFloat

}