/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.rider.rest.router.admin.api

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import edp.rider.common.RiderLogger
import edp.rider.rest.persistence.dal.{RelProjectUserDal, UserDal}
import edp.rider.rest.persistence.entities._
import edp.rider.rest.router.JsonProtocol._
import edp.rider.rest.router.{LoginClass, ResponseJson, ResponseSeqJson, SessionClass}
import edp.rider.rest.util.AuthorizationProvider
import edp.rider.rest.util.CommonUtils._
import edp.rider.rest.util.ResponseUtils._
import slick.jdbc.MySQLProfile.api._

import scala.util.{Failure, Success}


class UserAdminApi(userDal: UserDal, relProjectUserDal: RelProjectUserDal) extends BaseAdminApiImpl(userDal) with RiderLogger {

  def getByFilterRoute(route: String): Route = path(route) {
    get {
      parameter('visible.as[Boolean].?, 'email.as[String].?) {
        (visible, email) =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType != "admin") {
                riderLogger.warn(s"${session.userId} has no permission to access it.")
                complete(Forbidden, getHeader(403, session))
              }
              else {
                (visible, email) match {
                  case (_, Some(userEmail)) =>
                    onComplete(userDal.findByFilter(_.email === email).mapTo[Seq[User]]) {
                      case Success(users) =>
                        if (users.isEmpty) {
                          riderLogger.info(s"user ${session.userId} check email $userEmail doesn't exist.")
                          complete(OK, getHeader(200, session))
                        }
                        else {
                          riderLogger.warn(s"user ${session.userId} check email $userEmail already exists.")
                          complete(Conflict, getHeader(409, s"$email already exists", session))
                        }
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} check email $userEmail does exist failed", ex)
                        complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                    }
                  case (_, None) =>
                    onComplete(userDal.getUserProject(_.active === visible.getOrElse(true)).mapTo[Seq[UserProject]]) {
                      case Success(userProjects) =>
                        riderLogger.info(s"user ${session.userId} select all $route success.")
                        complete(OK, ResponseSeqJson[UserProject](getHeader(200, session), userProjects))
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} select all $route failed", ex)
                        complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                    }
                  case (_, _) =>
                    riderLogger.error(s"user ${session.userId} request url is not supported.")
                    complete(NotImplemented, getHeader(501, session))
                }
              }
          }
      }
    }

  }

  def postRoute(route: String): Route = path(route) {
    post {
      entity(as[SimpleUser]) {
        simple =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType != "admin") {
                riderLogger.warn(s"${session.userId} has no permission to access it.")
                complete(Forbidden, getHeader(403, session))
              }
              else {
                val user = User(0, simple.email, simple.password, simple.name, simple.roleType, active = true, currentSec, session.userId, currentSec, session.userId)
                onComplete(userDal.insert(user).mapTo[User]) {
                  case Success(row) =>
                    riderLogger.info(s"user ${session.userId} inserted user $row success.")
                    onComplete(userDal.getUserProject(_.id === row.id).mapTo[Seq[UserProject]]) {
                      case Success(userProject) =>
                        riderLogger.info(s"user ${session.userId} select user where id is ${row.id} success.")
                        complete(OK, ResponseJson[UserProject](getHeader(200, session), userProject.head))
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} select user where id is ${row.id} failed", ex)
                        complete(UnavailableForLegalReasons, getHeader(451, ex.toString, session))
                    }
                  case Failure(ex) =>
                    riderLogger.error(s"user ${session.userId} inserted user $user failed", ex)
                    if (ex.toString.contains("Duplicate entry"))
                      complete(Conflict, getHeader(409, s"${simple.email} already exists", session))
                    else
                      complete(UnavailableForLegalReasons, getHeader(451, ex.toString, session))
                }
              }
          }
      }
    }

  }


  def putRoute(route: String): Route = path(route) {
    put {
      entity(as[User]) {
        user =>
          authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
            session =>
              if (session.roleType != "admin")
                complete(Forbidden, getHeader(403, session))
              else {
                val userEntity = User(user.id, user.email, user.password, user.name, user.roleType, user.active, user.createTime, user.createBy, currentSec, session.userId)
                onComplete(userDal.update(userEntity)) {
                  case Success(result) =>
                    riderLogger.info(s"user ${session.userId} updated user $userEntity success.")
                    onComplete(userDal.getUserProject(_.id === userEntity.id).mapTo[Seq[UserProject]]) {
                      case Success(userProject) =>
                        riderLogger.info(s"user ${session.userId} select user where id is ${userEntity.id} success.")
                        complete(OK, ResponseJson[UserProject](getHeader(200, session), userProject.head))
                      case Failure(ex) =>
                        riderLogger.error(s"user ${session.userId} select user where id is ${userEntity.id} failed", ex)
                        complete(UnavailableForLegalReasons, getHeader(451, ex.toString, session))
                    }
                  case Failure(ex) =>
                    riderLogger.error(s"user ${session.userId} updated user $userEntity failed", ex)
                    complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
                }
              }
          }
      }
    }

  }

  def getNormalUserRoute(route: String): Route = path(route / "users") {
    get {
      authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
        session =>
          if (session.roleType != "admin") {
            riderLogger.warn(s"${session.userId} has no permission to access it.")
            complete(Forbidden, getHeader(403, session))
          }
          else {
            onComplete(userDal.findByFilter(user => user.active === true && user.roleType =!= "admin").mapTo[Seq[User]]) {
              case Success(users) =>
                riderLogger.info(s"user ${session.userId} select users where active is true and roleType is user success.")
                complete(OK, ResponseSeqJson[User](getHeader(200, session), users.sortBy(_.email)))
              case Failure(ex) =>
                riderLogger.error(s"user ${session.userId} select users where active is true and roleType is user failed", ex)
                complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
            }
          }
      }
    }

  }

  def getByProjectIdRoute(route: String): Route = path(route / LongNumber / "users") {
    id =>
      get {
        authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
          session =>
            if (session.roleType != "admin") {
              riderLogger.warn(s"${session.userId} has no permission to access it.")
              complete(Forbidden, getHeader(403, session))
            }
            else {
              onComplete(relProjectUserDal.getUserByProjectId(id).mapTo[Seq[User]]) {
                case Success(users) =>
                  riderLogger.info(s"user ${session.userId} select all users where project id is $id success.")
                  complete(OK, ResponseSeqJson[User](getHeader(200, session), users.sortBy(_.email)))
                case Failure(ex) =>
                  riderLogger.error(s"user ${session.userId} select all users where project id is $id failed", ex)
                  complete(UnavailableForLegalReasons, getHeader(451, ex.getMessage, session))
              }
            }
        }
      }
  }
}