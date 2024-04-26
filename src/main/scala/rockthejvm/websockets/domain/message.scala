package rockthejvm.websockets.domain

import java.util.UUID
import java.time.LocalDateTime
import rockthejvm.websockets.domain.user.{UserId, User}
import rockthejvm.websockets.domain.room.RoomId

object message {
  case class MessageId(id: UUID)
  case class MessageText(value: String)
  case class MessageTime(time: LocalDateTime)
  case class InsertMessage(
      id: MessageId,
      value: MessageText,
      time: MessageTime,
      userId: UserId,
      roomId: RoomId
  )
  case class FetchMessage(value: MessageText, from: User)
}
