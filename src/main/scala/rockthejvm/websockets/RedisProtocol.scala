package rockthejvm.websockets

import dev.profunktor.redis4cats.RedisCommands
import cats.syntax.all.*
import cats.Monad
import java.util.UUID
import rockthejvm.websockets.domain.user.*
import rockthejvm.websockets.domain.room.*

trait RedisProtocol[F[_]] {
  def createUser(user: User): F[Unit]
  def createRoom(room: Room): F[Unit]
  def getRoomFromName(roomname: String): F[Option[Room]]
  def getUsersRoomId(user: User): F[Option[RoomId]]
  def usernameExists(username: String): F[Boolean]
  def listUserIds(roomid: RoomId): F[Set[UserId]]
  def listRooms: F[List[String]]
  def roomExists(roomid: RoomId): F[Boolean]
  def mapUserToRoom(userid: UserId, roomid: RoomId): F[Unit]
  def addUserToRoom(userid: UserId, roomid: RoomId): F[Unit]
  def removeUserFromRoom(roomid: RoomId, userid: UserId): F[Unit]
  def deleteUserRoomMapping(userid: UserId): F[Unit]
  def deleteRoom(roomid: RoomId): F[Long]
  def deleteUser(userid: UserId): F[Long]
  def getSelectedUsers(
      userid: UserId,
      rest: List[UserId]
  ): F[Option[List[User]]]
  def chatState: F[String]
}

object RedisProtocol {
  def make[F[_]: Monad](
      redis: RedisCommands[F, String, String]
  ): F[RedisProtocol[F]] = {
    new RedisProtocol[F] {
      override def createUser(user: User): F[Unit] = {
        redis.hSet("users", user.toMap).void
      }

      override def createRoom(room: Room): F[Unit] = {
        redis.hSet("rooms", room.toMap).void
      }

      override def usernameExists(username: String): F[Boolean] = {
        redis.hVals("users").map { nameslist =>
          nameslist.exists(u => u == username)
        }
      }

      override def getRoomFromName(roomname: String): F[Option[Room]] = {
        redis.hGetAll("rooms").flatMap { rmap =>
          rmap.find(_._2 == roomname) match {
            case Some((i, n)) =>
              Room(
                UUID
                  .fromString(i),
                n
              )
                .map(_.toOption)
            case None => None.pure[F]
          }
        }
      }

      override def getUsersRoomId(user: User): F[Option[RoomId]] = {
        redis.hGet("userroomid", user.id.id.toString).map {
          case Some(roomid) => Some(RoomId(UUID.fromString(roomid)))
          case None         => None
        }
      }

      override def listUserIds(roomid: RoomId): F[Set[UserId]] = {
        redis.sMembers(s"room:${roomid.id.toString}").map { set =>
          set.map(id => UserId(UUID.fromString(id)))
        }
      }

      override def listRooms: F[List[String]] = {
        redis.hVals("rooms")
      }

      override def roomExists(roomid: RoomId): F[Boolean] = {
        redis.exists(s"room:${roomid.id.toString}")
      }

      override def mapUserToRoom(userid: UserId, roomid: RoomId): F[Unit] = {
        val urmap: Map[String, String] = Map(
          (userid.id.toString, roomid.id.toString)
        )
        redis.hSet("userroomid", urmap).void
      }

      override def addUserToRoom(userid: UserId, roomid: RoomId): F[Unit] = {
        redis.sAdd(s"room:${roomid.id.toString}", userid.id.toString).void
      }

      override def removeUserFromRoom(
          roomid: RoomId,
          userid: UserId
      ): F[Unit] = {
        redis.sRem(s"room:${roomid.id.toString}", userid.id.toString).void
      }

      override def deleteUserRoomMapping(userid: UserId): F[Unit] = {
        redis.hDel("userroomid", userid.id.toString).void
      }

      override def deleteRoom(roomid: RoomId): F[Long] = {
        redis.hDel("rooms", roomid.id.toString)
      }

      override def deleteUser(userid: UserId): F[Long] = {
        redis.hDel("users", userid.id.toString)
      }

      override def getSelectedUsers(
          userid: UserId,
          rest: List[UserId]
      ): F[Option[List[User]]] = {
        val idMap: F[Map[String, String]] =
          if (rest.isEmpty) {
            redis
              .hmGet(
                "users",
                userid.id.toString
              )
          } else {
            redis
              .hmGet(
                "users",
                userid.id.toString,
                rest.map(_.id.toString): _*
              )
          }

        idMap.flatMap { usermap =>
          usermap.toList.map { (id, name) =>
            User(UUID.fromString(id), name).map(_.toOption)
          }.sequence
        } map (_.sequence)
      }

      override def chatState: F[String] = {
        for {
          maybeusers <- redis.hLen("users")
          mayberooms <- redis.hLen("rooms")
          roomIds <- redis.hKeys("rooms")
          usersPerRoom <- roomIds.traverse { id =>
            redis.sMembers(s"room:$id").flatMap { uSet =>
              redis.hGet("rooms", id).flatMap {
                case Some(r) =>
                  val uids = uSet.map(id => UserId(UUID.fromString(id))).toList
                  getSelectedUsers(uids.head, uids.tail)
                    .map {
                      _.getOrElse(List.empty[User])
                        .map(_.name.name)
                        .mkString(s"$r Room Members:\n\t", "\n\t", "")
                    }
                case None =>
                  s"An error occured while fetching rooms".pure[F]
              }
            }
          }
        } yield s"""
                             |<!Doctype html>
                             |<title>Chat Server State</title>
                             |<body>
                             |<pre>Users: ${maybeusers.getOrElse("")}</pre>
                             |<pre>Rooms: ${mayberooms.getOrElse("")}</pre>
                             |<pre>Overview: 
                             |${usersPerRoom.mkString}
                             |</pre>
                             |</body>
                             |</html>
                        """.stripMargin
      }
    }.pure[F]
  }
}
