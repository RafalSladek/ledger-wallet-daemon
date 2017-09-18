package co.ledger.wallet.daemon

import co.ledger.wallet.daemon.controllers._
import co.ledger.wallet.daemon.database.DatabaseInitializationRoutine
import co.ledger.wallet.daemon.filters.{AuthenticationFilter, DemoUserAuthenticationFilter, LWDAutenticationFilter}
import co.ledger.wallet.daemon.mappers.AuthenticationExceptionMapper
import co.ledger.wallet.daemon.modules.{ConfigurationModule, DatabaseModule}
import co.ledger.wallet.daemon.services.PoolsService
import com.twitter.finatra.http.HttpServer
import com.twitter.finatra.http.filters.CommonFilters
import com.twitter.finatra.http.routing.HttpRouter
import com.typesafe.config.ConfigFactory
import djinni.NativeLibLoader

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object Server extends ServerImpl {

}

class ServerImpl extends HttpServer {
  val configuration = ConfigFactory.load()
  val profileName = Try(configuration.getString("database_engine")).toOption.getOrElse("sqlite3")
  val profile = {
    profileName match {
      case "sqlite3" =>
        slick.jdbc.SQLiteProfile
      case "postgres" =>
        slick.jdbc.PostgresProfile
      case "h2mem1" =>
        slick.jdbc.H2Profile
      case others => throw new Exception(s"Unknown database backend $others")
    }
  }

  override val modules = Seq(
    ConfigurationModule,
    DatabaseModule
  )

  override protected def configureHttp(router: HttpRouter): Unit =
    router
          .filter[CommonFilters]
          .filter[DemoUserAuthenticationFilter]
          .filter[LWDAutenticationFilter]
          .add[AuthenticationFilter, AccountsController]
          .add[AuthenticationFilter, CurrenciesController]
          .add[AuthenticationFilter, StatusController]
          .add[AuthenticationFilter, WalletPoolsController]
          .add[AuthenticationFilter, WalletsController]
          .exceptionMapper[AuthenticationExceptionMapper]

  override protected def warmup(): Unit = {
    super.warmup()
    NativeLibLoader.loadLibs()
    injector.instance[DatabaseInitializationRoutine](classOf[DatabaseInitializationRoutine]).perform() onComplete {
      case Success(_) =>
        info("Database initialized with routine successfully")
      case Failure(ex) =>
        error("Unable to perform database routine")
        error(ex)
        exitOnError(ex.getMessage)
    }
    val poolsService = injector.instance[PoolsService](classOf[PoolsService])
    Await.result(poolsService.initialize(), Duration.Inf)
  }
}