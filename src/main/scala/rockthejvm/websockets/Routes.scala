package rockthejvm.websockets

import fs2.io.file.Files
import cats.effect.Temporal
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder2
import cats.effect.std.Queue
import fs2.concurrent.Topic
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.StaticFile
import cats.syntax.all.*
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType
import cats.effect.kernel.Ref
import rockthejvm.websockets.domain.user.User
import fs2.Stream
import org.http4s.websocket.WebSocketFrame
import fs2.Pipe
import scala.concurrent.duration.*
import io.circe.generic.auto.*
import io.circe.syntax.*

class Routes[F[_]: Files: Temporal] extends Http4sDsl[F] {
  def service(
      wsb: WebSocketBuilder2[F],
      q: Queue[F, OutputMessage],
      t: Topic[F, OutputMessage],
      im: InputMessage[F],
      chatP: ChatProtocol[F]
  ): HttpApp[F] = {
    HttpRoutes.of[F] {
      case request @ GET -> Root / "chat.html" =>
        StaticFile
          .fromPath(
            fs2.io.file.Path("src/main/resources/chat.html"),
            Some(request)
          )
          .getOrElseF(NotFound())

      case GET -> Root / "metrics" =>
        def currentState: F[String] = {
          chatP.chatState
        }

        currentState.flatMap { currState =>
          Ok(currState, `Content-Type`(MediaType.text.html))
        }

      case GET -> Root / "ws" =>
        for {
          uRef <- Ref.of[F, Option[User]](None)
          uQueue <- Queue.unbounded[F, OutputMessage]
          ws <- wsb.build(
            send(t, uQueue, uRef),
            receive(chatP, im, uRef, q, uQueue)
          )
        } yield ws
    }
  }.orNotFound

  private def handleWebSocketStream(
      wsf: Stream[F, WebSocketFrame],
      im: InputMessage[F],
      chatP: ChatProtocol[F],
      uRef: Ref[F, Option[User]]
  ): Stream[F, OutputMessage] = {
    for {
      sf <- wsf
      maybeuser <- Stream.eval(uRef.get)
      om <- Stream.evalSeq(
        sf match {
          case WebSocketFrame.Text(text, _) =>
            im.parse(uRef, text)
          case WebSocketFrame.Close(_) =>
            chatP.disconnect(maybeuser)
        }
      )
    } yield om
  }

  private def receive(
      chatP: ChatProtocol[F],
      im: InputMessage[F],
      uRef: Ref[F, Option[User]],
      q: Queue[F, OutputMessage],
      uQueue: Queue[F, OutputMessage]
  ): Pipe[F, WebSocketFrame, Unit] = { wsf =>
    handleWebSocketStream(wsf, im, chatP, uRef)
      .evalMap { m =>
        uRef.get.flatMap {
          case Some(_) =>
            q.offer(m)
          case None =>
            uQueue.offer(m)
        }
      }
      .concurrently {
        Stream
          .awakeEvery(30.seconds)
          .map(_ => KeepAlive)
          .foreach(uQueue.offer)
      }
  }

  private def filterMsg(
      msg: OutputMessage,
      userRef: Ref[F, Option[User]]
  ): F[Boolean] = {
    msg match {
      case DiscardMessage => false.pure[F]
      case sendtouser @ SendToUser(_, _) =>
        userRef.get.map { _.fold(false)(u => sendtouser.forUser(u)) }
      case chatmsg @ ChatMsg(_, _, _) =>
        userRef.get.map { _.fold(false)(u => chatmsg.forUser(u)) }
      case _ => true.pure[F]
    }
  }

  private def processMsg(msg: OutputMessage): WebSocketFrame = {
    msg match {
      case KeepAlive => WebSocketFrame.Ping()
      case msg @ _   => WebSocketFrame.Text(msg.asJson.noSpaces)
    }
  }

  private def send(
      t: Topic[F, OutputMessage],
      uQueue: Queue[F, OutputMessage],
      uRef: Ref[F, Option[User]]
  ): Stream[F, WebSocketFrame] = {
    def uStream =
      Stream
        .fromQueueUnterminated(uQueue)
        .filter {
          case DiscardMessage => false
          case _              => true
        }
        .map(processMsg)

    def mainStream =
      t.subscribe(maxQueued = 1000)
        .evalFilter(filterMsg(_, uRef))
        .map(processMsg)

    Stream(uStream, mainStream).parJoinUnbounded
  }

}
