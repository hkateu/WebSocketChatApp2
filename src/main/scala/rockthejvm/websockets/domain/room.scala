package rockthejvm.websockets.domain

import java.util.UUID
import cats.data.Validated
import cats.syntax.all.*
import cats.*
import rockthejvm.websockets.domain.validateutility.*

object room {
  case class RoomId(id: UUID)
  case class RoomName(name: String)
  case class Room(id: RoomId, name: RoomName) {
    def toMap = Map((id.id.toString, name.name))
  }
  object Room {
    def apply[F[_]: Applicative](
        id: UUID,
        name: String
    ): F[Validated[String, Room]] =
      validateItem(name, new Room(RoomId(id), RoomName(name)), "Room").pure[F]
  }
}
