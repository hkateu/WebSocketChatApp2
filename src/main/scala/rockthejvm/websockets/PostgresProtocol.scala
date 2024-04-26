package rockthejvm.websockets

import fs2.Stream
import java.time.LocalDateTime
import cats.effect.std.UUIDGen
import cats.effect.*
import skunk.*
import cats.syntax.all.*
import skunk.implicits.*
import rockthejvm.websockets.domain.user.*
import rockthejvm.websockets.domain.room.*
import rockthejvm.websockets.domain.message.*
import rockthejvm.websockets.codecs.codecs.*
import cats.data.Validated.{Valid, Invalid}

trait PostgresProtocol[F[_]] {
  def createUser(name: String): F[Either[String, User]]
  def createRoom(name: String): F[Either[String, Room]]
  def deleteRoom(roomId: RoomId): F[Unit]
  def deleteUser(userId: UserId): F[Unit]
  def saveMessage(
      message: String,
      time: LocalDateTime,
      userId: UserId,
      roomId: RoomId
  ): F[Unit]
  def fetchMessages(roomId: RoomId): F[Stream[F, FetchMessage]]
  def deleteRoomMessages(roomId: RoomId): F[Unit]
}

object PostgresProtocol {
  def make[F[_]: UUIDGen: Concurrent](
      postgres: Resource[F, Session[F]]
  ): F[PostgresProtocol[F]] =
    postgres.use { session =>
      new PostgresProtocol[F] {
        import SqlCommands.*

        private def deleteUserOrRoom[A](id: A, command: Command[A]): F[Unit] =
          session.prepare(command).flatMap { cmd =>
            cmd.execute(id).void
          }

        override def createUser(name: String): F[Either[String, User]] =
          session.prepare(insertUser).flatMap { cmd =>
            UUIDGen.randomUUID.flatMap { id =>
              User(id, name).flatMap {
                case Valid(u) =>
                  cmd.execute(u).as(Right(u))
                case Invalid(err) =>
                  Left(err).pure[F]
              }
            }
          }

        override def createRoom(name: String): F[Either[String, Room]] =
          session.prepare(insertRoom).flatMap { cmd =>
            UUIDGen.randomUUID.flatMap { id =>
              Room(id, name).flatMap {
                case Valid(r) =>
                  cmd.execute(r).as(Right(r))
                case Invalid(err) =>
                  Left(err).pure[F]
              }
            }
          }

        override def deleteUser(uId: UserId): F[Unit] =
          deleteUserOrRoom[UserId](uId, delUser)

        override def deleteRoom(rId: RoomId): F[Unit] =
          deleteUserOrRoom[RoomId](rId, delRoom)

        override def saveMessage(
            message: String,
            time: LocalDateTime,
            userId: UserId,
            roomId: RoomId
        ): F[Unit] =
          session.prepare(insertMessage).flatMap { cmd =>
            UUIDGen.randomUUID.flatMap { id =>
              cmd
                .execute(
                  InsertMessage(
                    MessageId(id),
                    MessageText(message),
                    MessageTime(time),
                    userId,
                    roomId
                  )
                )
                .void
            }
          }

        override def fetchMessages(roomId: RoomId): F[Stream[F, FetchMessage]] =
          session.prepare(getMessage).map { cmd =>
            cmd.stream(roomId, 32)
          }

        override def deleteRoomMessages(roomId: RoomId): F[Unit] =
          session.prepare(delMessages).flatMap { cmd =>
            cmd.execute(roomId).void
          }
      }.pure[F]
    }
}

private object SqlCommands {
  // users
  val usercodec: Codec[User] =
    (userId ~ userName).imap { case i ~ n =>
      User(i, n)
    }(u => (u.id, u.name))

  val insertUser: Command[User] =
    sql"""
            INSERT INTO users
            VALUES($usercodec)
        """.command

  val delUser: Command[UserId] =
    sql"""
            UPDATE users
            SET name = "deletedUser"
            WHERE id = $userId
        """.command

  // rooms
  val roomcodec: Codec[Room] =
    (roomId ~ roomName).imap { case i ~ n =>
      Room(i, n)
    }(u => (u.id, u.name))

  val insertRoom: Command[Room] =
    sql"""
            INSERT INTO rooms
            VALUES($roomcodec)
        """.command

  val delRoom: Command[RoomId] =
    sql"""
            DELETE FROM rooms
            WHERE id = $roomId
        """.command

  // messages
  val messagecodec: Codec[InsertMessage] =
    (messageId ~ messageText ~ messageTime ~ userId ~ roomId).imap {
      case mi ~ mt ~ mtm ~ ui ~ ri => InsertMessage(mi, mt, mtm, ui, ri)
    }(m => (((((m.id), m.value), m.time), m.userId), m.roomId))

  val fetchmessagecodec: Codec[FetchMessage] =
    (messageText ~ userId ~ userName).imap { case mt ~ ui ~ un =>
      FetchMessage(mt, User(ui, un))
    }(m => (((m.value), m.from.id), m.from.name))

  val insertMessage: Command[InsertMessage] =
    sql"""
            INSERT INTO messages
            VALUES($messagecodec)
        """.command

  val getMessage: Query[RoomId, FetchMessage] =
    sql"""
            SELECT messages.message, users.id, users.name 
            FROM messages 
            INNER JOIN users 
            ON messages.user_id = users.id 
            WHERE messages.room_id = $roomId;
        """.query(fetchmessagecodec)

  val delMessages: Command[RoomId] =
    sql"""
            DELETE FROM messages 
            WHERE room_id = $roomId
        """.command
}
