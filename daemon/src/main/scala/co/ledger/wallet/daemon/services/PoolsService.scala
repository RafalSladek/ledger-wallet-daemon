package co.ledger.wallet.daemon.services

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

import co.ledger.core.{DatabaseBackend, DynamicObject, WalletPoolBuilder, WalletPool => CoreWalletPool}

import scala.concurrent.Future
import co.ledger.core.implicits._
import co.ledger.wallet.daemon.async.SerialExecutionContext
import co.ledger.wallet.daemon.database.{Pool, User}
import co.ledger.wallet.daemon.libledger_core.async.ScalaThreadDispatcher
import co.ledger.wallet.daemon.libledger_core.crypto.SecureRandomRNG
import co.ledger.wallet.daemon.libledger_core.debug.NoOpLogPrinter
import co.ledger.wallet.daemon.libledger_core.filesystem.ScalaPathResolver
import co.ledger.wallet.daemon.libledger_core.net.{ScalaHttpClient, ScalaWebSocketClient}
import co.ledger.wallet.daemon.utils.HexUtils
import org.bitcoinj.core.Sha256Hash
import co.ledger.wallet.daemon.exceptions.ResourceNotFoundException
import co.ledger.wallet.daemon.models

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class PoolsService extends DaemonService {
  import PoolsService._
  private val _writeContext = SerialExecutionContext.newInstance()
  private val _readContext = scala.concurrent.ExecutionContext.Implicits.global
  private val dbDao = DatabaseService.dbDao

  def createPool(user: User, poolName: String, configuration: PoolConfiguration): Future[models.WalletPool] = {
    implicit val ec = _writeContext
    info(s"Start to create pool: poolName=$poolName configuration=$configuration userPubKey=${user.pubKey}")
    val newPool = Pool(poolName, user.id.get, configuration.toString)
    dbDao.insertPool(newPool).flatMap((_) =>buildPool(newPool)).map(models.newInstance(_)).flatten.map { pool =>
      info(s"Finish creating pool: $pool")
      pool
    }
  }

  def pools(user: User): Future[Seq[models.WalletPool]] = {
    info(s"Obtain pools with params: userPubKey=${user.pubKey}")
    val modelPools  = for {
      walletPoolsSeq <- dbDao.getPools(user.id.get).flatMap(p => Future.sequence(p.map(mapPool).toSeq))
      pools = walletPoolsSeq.map { walletPool =>
        for (pool <- models.newInstance(walletPool)) yield pool
      }} yield Future.sequence(pools)
    modelPools.flatten
  }

  def pool(user: User, poolName: String): Future[models.WalletPool] = {
    info(s"Obtain pool with params: poolName=$poolName userPubKey=${user.pubKey}")
    val p: CoreWalletPool = _pools.getOrDefault(poolIdentifier(user.id.get, poolName), null)
    if (p != null) {
      info(s"Pool obtained: ${p.getName}")
      models.newInstance(p)
    } else
      Future.failed(ResourceNotFoundException(classOf[CoreWalletPool], poolName))
  }

  def removePool(user: User, poolName: String): Future[Unit] = {
    implicit val ec = _writeContext
    info(s"Start to remove pool: poolName=$poolName userPubKey=${user.pubKey}")
    pool(user, poolName).map {(p) =>
      // p.release() TODO once WalletPool#release exists
      dbDao.deletePool(poolName, user.id.get).map {(_) =>
        _pools.remove(poolIdentifier(user.id.get, poolName))
        info(s"Finish removing pool: $p")
      }
    }
  }

  private def mapPool(pool: Pool): Future[CoreWalletPool] = {
    val p = _pools.getOrDefault(poolIdentifier(pool), null)
    if (p != null)
      Future.successful(p)
    else
      Future.failed(ResourceNotFoundException(classOf[CoreWalletPool], pool.name))
  }

  private def poolIdentifier(pool: Pool): String = poolIdentifier(pool.userId, pool.name)
  private def poolIdentifier(userId: Long, poolName: String): String = HexUtils.valueOf(Sha256Hash.hash(s"${userId}:${poolName}".getBytes))

  private def buildPool(pool: Pool): Future[CoreWalletPool] = {
    val identifier = poolIdentifier(pool)
    WalletPoolBuilder.createInstance()
      .setHttpClient(new ScalaHttpClient)
      .setWebsocketClient(new ScalaWebSocketClient)
      .setLogPrinter(new NoOpLogPrinter(dispatcher.getMainExecutionContext))
      .setThreadDispatcher(dispatcher)
      .setPathResolver(new ScalaPathResolver(identifier))
      .setRandomNumberGenerator(new SecureRandomRNG)
      .setDatabaseBackend(DatabaseBackend.getSqlite3Backend)
      .setConfiguration(DynamicObject.newInstance())
      .setName(pool.name)
      .build() map {(p) =>
        _pools.put(identifier, p)
      debug(s"Built core wallet pool: identifier=${identifier} poolName=${p.getName}")
      p
    }
  }

  private val _pools = new ConcurrentHashMap[String, CoreWalletPool]()
  val dispatcher = new ScalaThreadDispatcher(scala.concurrent.ExecutionContext.Implicits.global)
  def initialize(): Unit = {
    dbDao.getPools.map(pools => pools.map(buildPool))
  }
}

object PoolsService {

  case class PoolConfiguration() {
    override def toString: String = ""
  }

}