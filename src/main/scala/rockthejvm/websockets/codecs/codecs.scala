package rockthejvm.websockets.codecs

import skunk.*
import skunk.codec.all.*
import rockthejvm.websockets.domain.user.{UserId, UserName}
import rockthejvm.websockets.domain.room.{RoomId, RoomName}
import rockthejvm.websockets.domain.message.{
  MessageId,
  MessageText,
  MessageTime
}

object codecs {
  val userId: Codec[UserId] = uuid.imap[UserId](UserId(_))(_.id)
  val userName: Codec[UserName] =
    varchar(255).imap[UserName](UserName(_))(_.name)
  val roomId: Codec[RoomId] = uuid.imap[RoomId](RoomId(_))(_.id)
  val roomName: Codec[RoomName] =
    varchar(255).imap[RoomName](RoomName(_))(_.name)
  val messageId: Codec[MessageId] = uuid.imap[MessageId](MessageId(_))(_.id)
  val messageText: Codec[MessageText] =
    text.imap[MessageText](MessageText(_))(_.value)
  val messageTime: Codec[MessageTime] =
    timestamp.imap[MessageTime](MessageTime(_))(_.time)
}
