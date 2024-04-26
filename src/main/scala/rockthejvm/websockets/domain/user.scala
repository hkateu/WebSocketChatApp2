package rockthejvm.websockets.domain

import java.util.UUID
import cats.Applicative
import cats.data.Validated
import rockthejvm.websockets.domain.validateutility.*
import cats.syntax.all.*

object user {
  case class UserId(id: UUID)
  case class UserName(name: String)
  case class User(id: UserId, name: UserName) {
    def toMap = Map((id.id.toString, name.name))
  }
  object User {
    def apply[F[_]: Applicative](
        id: UUID,
        name: String
    ): F[Validated[String, User]] =
      validateItem(name, new User(UserId(id), UserName(name)), "User name")
        .pure[F]
  }
}
