package io.chrisdavenport.http4s.log4cats.contextlog

import cats._
import cats.syntax.all._
import cats.effect._
import fs2.{Stream, Pure}
import org.http4s.{Request, Response, Message, MediaType}
import cats.effect.Outcome
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.extras.LogLevel
import scala.concurrent.duration._
import org.http4s.Charset


private[contextlog] object SharedStructuredLogging {
  private[contextlog] def pureRequest[F[_]](req: Request[F]): Request[Pure] = Request(req.method, req.uri, req.httpVersion, req.headers, Stream.empty, req.attributes)
  private[contextlog] def pureResponse[F[_]](resp: Response[F]): Response[Pure] = Response(resp.status, resp.httpVersion, resp.headers, Stream.empty, resp.attributes)

  private[contextlog] def outcomeContext[F[_], E, A](outcome: Outcome[F, E, A]): (String, String) = {
    outcome match {
      case Outcome.Canceled() => "exit.case" -> "canceled"
      case Outcome.Errored(_) => "exit.case" -> "errored"
      case Outcome.Succeeded(_) => "exit.case" -> "succeeded"
    }
  }

  private[contextlog] def logLevelAware[F[_]: Applicative](
    logger: StructuredLogger[F],
    ctx: Map[String, String],
    prelude: Request[Pure],
    outcome: Outcome[Option, Throwable, Response[Pure]],
    now: FiniteDuration,
    removedContextKeys: Set[String],
    logLevel: (Request[Pure], Outcome[Option, Throwable, Response[Pure]]) => Option[LogLevel],
    logMessage: (Request[Pure], Outcome[Option, Throwable, Response[Pure]], FiniteDuration) => String,
  ): F[Unit] = {
    (logLevel(prelude, outcome), outcome) match {
      case (None, _) => Applicative[F].unit
      case (Some(LogLevel.Trace), Outcome.Errored(e)) =>
        logger.trace(ctx -- removedContextKeys, e)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Trace), _) =>
        logger.trace(ctx -- removedContextKeys)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Debug), Outcome.Errored(e)) =>
        logger.debug(ctx -- removedContextKeys, e)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Debug), _) =>
        logger.debug(ctx -- removedContextKeys)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Info), Outcome.Errored(e)) =>
        logger.info(ctx -- removedContextKeys, e)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Info), _) =>
        logger.info(ctx -- removedContextKeys)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Warn), Outcome.Errored(e)) =>
        logger.warn(ctx -- removedContextKeys, e)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Warn), _) =>
        logger.warn(ctx -- removedContextKeys)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Error), Outcome.Errored(e)) =>
        logger.error(ctx -- removedContextKeys, e)(logMessage(prelude, outcome, now))
      case (Some(LogLevel.Error), _) =>
        logger.error(ctx -- removedContextKeys)(logMessage(prelude, outcome, now))
    }
  }

  private[contextlog] def logBody[F[_]: Concurrent](message: Message[F]): F[String] = {
    val isBinary = message.contentType.exists(_.mediaType.binary)
    val isJson = message.contentType.exists(mT =>
      mT.mediaType == MediaType.application.json || mT.mediaType.subType.endsWith("+json")
    )
    if (!isBinary || isJson) {
      message
        .bodyText(implicitly, message.charset.getOrElse(Charset.`UTF-8`))
        .compile
        .string
    }else message.body.compile.to(scodec.bits.ByteVector).map(_.toHex)

  }



}