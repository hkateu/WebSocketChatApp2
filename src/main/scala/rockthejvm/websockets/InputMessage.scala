package rockthejvm.websockets

import cats.effect.kernel.Ref
import rockthejvm.websockets.domain.user.*
import cats.Monad
import cats.syntax.all.*
import cats.parse.Parser
import cats.parse.Parser.char
import cats.parse.Rfc5234.{alpha, sp, wsp}
import cats.Applicative
import cats.*

trait InputMessage[F[_]] {
  def parse(
      userRef: Ref[F, Option[User]],
      text: String
  ): F[List[OutputMessage]]
}

case class TextCommand(left: String, right: Option[String])

object InputMessage {
  def make[F[_]: Monad](
      chatP: ChatProtocol[F]
  ): F[InputMessage[F]] = {
    new InputMessage[F] {
      override def parse(
          userRef: Ref[F, Option[User]],
          text: String
      ): F[List[OutputMessage]] = {
        text.trim match {
          case "" => List(DiscardMessage).pure[F]
          case txt =>
            userRef.get.flatMap {
              case Some(user) => procesText4Reg(user, txt, chatP)
              case None => processText4UnReg(txt, chatP, userRef)
            }
        }
      }
    }.pure[F]
  }

  private def commandParser: Parser[TextCommand] = {
    val leftSide = (char('/').string ~ alpha.rep.string).string
    val rightSide: Parser[(Unit, String)] = sp ~ alpha.rep.string
    ((leftSide ~ rightSide.?) <* wsp.rep.?).map((left, right) =>
      TextCommand(left, right.map((_, s) => s))
    )
  }

  private def parseToTextCommand(
      value: String
  ): Either[Parser.Error, TextCommand] = {
    commandParser.parseAll(value)
  }

  private def processText4UnReg[F[_]: Monad](
      text: String,
      chatP: ChatProtocol[F],
      userRef: Ref[F, Option[User]]
  ): F[List[OutputMessage]] = {
    if (text.charAt(0) == '/') {
      parseToTextCommand(text).fold(
        _ =>
          List(
            ParsingError(
              None,
              "Characters after '/' must be between A-Z or a-z"
            )
          ).pure[F],
        v =>
          v match {
            case TextCommand("/name", Some(n)) =>
              chatP.register(n).flatMap{
                case sr @ SuccessfulRegistration(u,_) => 
                  for {
                    _ <- userRef.update(_ => Some(u))
                    om <- chatP.enterRoom(u, "Default")
                    help <- chatP.help(u)
                  } yield sr :: (help :: om)
                case ParsingError(None, err) =>
                  List(ParsingError(None, err)).pure[F]
                case _ =>  List(DiscardMessage).pure[F]
              }
            case _ =>
              List(UnsupportedCommand(None)).pure[F]
          }
      )
    } else {
      List(Register(None)).pure[F]
    }
  }

  private def procesText4Reg[F[_]: Applicative](
      user: User,
      text: String,
      chatP: ChatProtocol[F],
  ): F[List[OutputMessage]] = {
    if (text.charAt(0) == '/') {
      parseToTextCommand(text).fold(
        _ =>
          List(
            ParsingError(
              None,
              "Characters after '/' must be between A-Z or a-z"
            )
          ).pure[F],
        v =>
          v match {
            case TextCommand("/name", Some(n)) =>
              List(ParsingError(Some(user), "You can't register again")).pure[F]
            case TextCommand("/room", Some(r)) =>
              chatP.enterRoom(user, r)
            case TextCommand("/help", None) =>
              chatP.help(user).map(List(_))
            case TextCommand("/rooms", None) =>
              chatP.listRooms(user)
            case TextCommand("/members", None) =>
              chatP.listMembers(user)
            case _ => List(UnsupportedCommand(Some(user))).pure[F]
          }
      )
    } else {
      chatP.chat(user, text)
    }
  }
}
