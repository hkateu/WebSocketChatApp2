package rockthejvm.websockets

import rockthejvm.websockets.domain.user.*
import cats.Monad
import cats.syntax.all.*
import cats.effect.std.UUIDGen
import cats.effect.kernel.Concurrent
import rockthejvm.websockets.domain.room.*
import cats.FlatMap
import rockthejvm.websockets.domain.message.*
import java.time.LocalDateTime

trait ChatProtocol[F[_]] {
  def register(name: String): F[OutputMessage]
  def enterRoom(user: User, room: String): F[List[OutputMessage]]
  def chat(user: User, text: String): F[List[OutputMessage]]
  def help(user: User): F[OutputMessage]
  def listRooms(user: User): F[List[OutputMessage]]
  def listMembers(user: User): F[List[OutputMessage]]
  def disconnect(maybeuser: Option[User]): F[List[OutputMessage]]
  def chatState: F[String]
}
// def isUsernameInUse(name: String): F[Boolean]

object ChatProtocol {

  def make[F[_]: Monad: UUIDGen: Concurrent](
      redisP: RedisProtocol[F],
      postgresP: PostgresProtocol[F]
  ): F[ChatProtocol[F]] = {
    new ChatProtocol[F] {
      override def register(name: String): F[OutputMessage] = {
        for {
          userExists <- redisP.usernameExists(name)
          maybeUser <-
            if(userExists == true) {
              Left("User name exists").pure[F]
            } else {
              postgresP.createUser(name)
            }
          om <- maybeUser match {
            case Right(u: User) =>
              redisP.createUser(u) *>
                SuccessfulRegistration(u).pure[F]
            case Left(err: String) =>
              ParsingError(None, err)
                .pure[F]
          }
        } yield om
      }

      override def enterRoom(
          user: User,
          room: String
      ): F[List[OutputMessage]] = {
        redisP.getRoomFromName(room).flatMap {
          case Some(r) =>
            redisP.getUsersRoomId(user).flatMap {
              case Some(usersroomid) =>
                if (usersroomid == r.id) {
                  List(
                    SendToUser(
                      user,
                      s"You are already in the ${r.name.name} room"
                    )
                  ).pure[F]
                } else {
                  transferUserToRoom(redisP, postgresP, user, r)
                }
              case None =>
                addToRoom(redisP, postgresP, user, r)
            }
          case None =>
            createRoom(redisP, postgresP, room).flatMap {
              case Right(r) =>
                transferUserToRoom(redisP, postgresP, user, r)
              case Left(err) =>
                List(
                  ParsingError(
                    Some(user),
                    err
                  )
                ).pure[F]
            }

        }
      }

      override def chat(user: User, text: String): F[List[OutputMessage]] = {
        redisP.getUsersRoomId(user).flatMap {
          case Some(roomid) =>
            postgresP.saveMessage(text, LocalDateTime.now(), user.id, roomid) *>
              broadcastMessage(redisP, roomid, ChatMsg(user, user, text))
          case None =>
            List(SendToUser(user, "You are not currently in a room")).pure[F]
        }
      }

      override def help(user: User): F[OutputMessage] = {
        val text = """Commands:
                        | /help             - Show this text
                        | /room <room name> - Change to specified room
                        | /rooms            - List all rooms
                        | /members          - List members in current room
                    """.stripMargin
        SendToUser(user, text).pure[F]
      }

      override def listRooms(user: User): F[List[OutputMessage]] = {
        redisP.listRooms.map { rooms =>
          val roomList = rooms.toList.sorted.mkString("Rooms:\n\t", "\n\t", "")
          List(SendToUser(user, roomList))
        }
      }

      override def listMembers(user: User): F[List[OutputMessage]] = {
        val membersList: F[String] = redisP.getUsersRoomId(user).flatMap {
          case Some(roomid) =>
            redisP.listUserIds(roomid).flatMap { u =>
              redisP.getSelectedUsers(u.toList.head, u.toList.tail).map {
                maybelist =>
                  maybelist match {
                    case Some(lu) =>
                      lu.map(_.name.name)
                        .sorted
                        .mkString("Room Members:\n\t", "\n\t", "")
                    case None => ""
                  }
              }
            }
          case None => "You are not currently in a room".pure[F]
        }
        membersList.map(mlist => List(SendToUser(user, mlist)))
      }

      override def disconnect(
          maybeuser: Option[User]
      ): F[List[OutputMessage]] = {
        maybeuser match {
          case Some(user) =>
            postgresP.deleteUser(user.id) *>
              redisP.deleteUser(user.id) *>
              removeFromCurrentRoom(redisP, postgresP, user)
          case None => List.empty[OutputMessage].pure[F]
        }
      }

      override def chatState: F[String] = redisP.chatState
    }.pure[F]
  }

  private def transferUserToRoom[F[_]: Monad: UUIDGen: Concurrent](
      redisP: RedisProtocol[F],
      postgresP: PostgresProtocol[F],
      user: User,
      room: Room
  ): F[List[OutputMessage]] =
    val leaveMessages = removeFromCurrentRoom(redisP, postgresP, user)
    val enterMessages = addToRoom(redisP, postgresP, user, room)
    for {
      leave <- leaveMessages
      enter <- enterMessages
    } yield leave ++ enter

  private def createRoom[F[_]: Monad](
      redisP: RedisProtocol[F],
      postgresP: PostgresProtocol[F],
      room: String
  ): F[Either[String, Room]] =
    postgresP.createRoom(room).flatMap {
      case v @ Right(r) =>
        redisP.createRoom(r) *>
          v.pure[F]
      case l @ Left(err) => l.pure[F]
    }

  private def addToRoom[F[_]: Monad: UUIDGen: Concurrent](
      redisP: RedisProtocol[F],
      postgresP: PostgresProtocol[F],
      user: User,
      room: Room
  ): F[List[OutputMessage]] = {
    for {
      _ <- redisP.addUserToRoom(user.id, room.id)
      _ <- redisP.mapUserToRoom(user.id, room.id)
      previousMessages <- fetchRoomMessages(postgresP, room.id, user)
      om <- broadcastMessage(
        redisP,
        room.id,
        SendToUser(
          user,
          s"${user.name.name} has joined the ${room.name.name} room"
        )
      )
    } yield previousMessages ++ om
  }

  private def broadcastMessage[F[_]: Monad: UUIDGen](
      redisP: RedisProtocol[F],
      roomid: RoomId,
      om: OutputMessage
  ): F[List[OutputMessage]] = {
    redisP.listUserIds(roomid).flatMap { uset =>
      val userlist = uset.toList
      if (userlist.isEmpty) {
        List.empty[OutputMessage].pure[F]
      } else {
        redisP.getSelectedUsers(userlist.head, userlist.tail).map { maybelist =>
          maybelist match {
            case Some(ulist) =>
              ulist.map { u =>
                om match {
                  case SendToUser(user, msg)  => SendToUser(u, msg)
                  case ChatMsg(from, to, msg) => ChatMsg(from, u, msg)
                  case _                      => DiscardMessage
                }
              }
            case None => List.empty[OutputMessage]
          }
        }
      }

    }
  }

  private def removeFromCurrentRoom[F[_]: Monad: UUIDGen](
      redisP: RedisProtocol[F],
      postgresP: PostgresProtocol[F],
      user: User
  ): F[List[OutputMessage]] = {
    redisP.getUsersRoomId(user).flatMap {
      case Some(roomid) =>
        for {
          _ <- redisP.removeUserFromRoom(roomid, user.id)
          _ <- redisP.roomExists(roomid).flatMap { b =>
            println(b)
            if (b == true) { ().pure[F] }
            else {
              redisP.deleteRoom(roomid) *>
                postgresP.deleteRoomMessages(roomid) *>
                postgresP.deleteRoom(roomid)
            }
          }
          _ <- redisP.deleteUserRoomMapping(user.id)
          om <- broadcastMessage(
            redisP,
            roomid,
            SendToUser(user, s"${user.name.name} has left the room")
          )
        } yield om
      case None =>
        List.empty[OutputMessage].pure[F]
    }
  }

  private def fetchRoomMessages[F[_]: FlatMap: Concurrent](
      postgresP: PostgresProtocol[F],
      roomid: RoomId,
      user: User
  ): F[List[OutputMessage]] =
    postgresP
      .fetchMessages(roomid)
      .flatMap {
        _.map { case FetchMessage(msg, from) =>
          ChatMsg(from, user, msg.value)
        }.compile.toList
      }
}
