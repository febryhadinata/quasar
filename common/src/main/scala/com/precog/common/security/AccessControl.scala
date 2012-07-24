/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.common
package security

import blueeyes._
import blueeyes.bkka.AkkaDefaults
import blueeyes.json.JPath
import org.joda.time.DateTime

import com.weiglewilczek.slf4s.Logging

import scala.collection.mutable

import blueeyes.persistence.mongo._
import blueeyes.persistence.cache._

import blueeyes.json.JsonAST._
import blueeyes.json.xschema.{ ValidatedExtraction, Extractor, Decomposer }
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.json.xschema.Extractor._

import java.util.concurrent.TimeUnit._

import scalaz._
import scalaz.std.set._
import scalaz.syntax.monad._
import scalaz.syntax.traverse._

trait AccessControl[M[+_]] {
  def mayAccessPath(uid: UID, path: Path, pathAccess: PathAccess): M[Boolean]
  def mayAccessData(uid: UID, path: Path, owners: Set[UID], dataAccess: DataAccess): M[Boolean]
  def mayAccess(uid: UID, path: Path, ownders: Set[UID], accessType: AccessType): M[Boolean]
}

class UnlimitedAccessControl[M[+_]: Pointed] extends AccessControl[M] {
  def mayAccessPath(uid: UID, path: Path, pathAccess: PathAccess) = Pointed[M].point(true)
  def mayAccessData(uid: UID, path: Path, owners: Set[UID], dataAccess: DataAccess) = Pointed[M].point(true)
  def mayAccess(uid: UID, path: Path, ownders: Set[UID], accessType: AccessType) = Pointed[M].point(true)
}

class TokenManagerAccessControl[M[+_]](tokens: TokenManager[M])(implicit M: Monad[M]) extends AccessControl[M] with Logging {
  def mayAccessPath(uid: UID, path: Path, pathAccess: PathAccess): M[Boolean] = 
    pathAccess match {
      case PathRead => mayAccess(uid, path, Set(uid), ReadPermission) 
      case PathWrite => mayAccess(uid, path, Set.empty, WritePermission)
    }

  def mayAccessData(uid: UID, path: Path, owners: Set[UID], dataAccess: DataAccess): M[Boolean] = 
    mayAccess(uid, path, owners, ReadPermission)
 
  def mayAccess(uid: TokenID, path: Path, owners: Set[UID], accessType: AccessType): M[Boolean] = {
    tokens.findToken(uid).flatMap{ _.map { t => 
      logger.debug("Checking %s access to %s from token %s with owners: %s".format(accessType, path, uid, owners))
       hasValidPermissions(t, path, owners, accessType)
    }.getOrElse(M.point(false)) }
  }

  def hasValidPermissions(t: Token, path: Path, owners: Set[UID], accessType: AccessType): M[Boolean] = {
    def exists(fs: Set[M[Boolean]]): M[Boolean] = {
      fs.sequence map { _ reduceOption { _ || _ } getOrElse false }
    }
    
    def forall(fs: Set[M[Boolean]]): M[Boolean] = {
      fs.sequence map { _ reduceOption { _ && _ } getOrElse true }
    }

    accessType match {
      case WritePermission =>
        exists(t.grants.map{ gid =>
          tokens.findGrant(gid).flatMap( _.map { 
            case g @ Grant(_, _, WritePermission(p, _)) =>
              isValid(g).map { _ && p.equalOrChild(path) }
            case _ => M.point(false)
          }.getOrElse(M.point(false))
        )})
      case OwnerPermission =>
        exists(t.grants.map{ gid =>
          tokens.findGrant(gid).flatMap( _.map { 
            case g @ Grant(_, _, OwnerPermission(p, _)) =>
              isValid(g).map { _ && p.equalOrChild(path) }
            case _ => M.point(false)
          }.getOrElse(M.point(false))
        )})
      case ReadPermission =>
        if(owners.isEmpty) { logger.debug("Empty owners == no read permission"); M.point(false) }
        else forall(owners.map { owner =>
          exists(t.grants.map{ gid =>
            tokens.findGrant(gid).flatMap( _.map {
              case g @ Grant(_, _, ReadPermission(p, o, _)) =>
                isValid(g).map { valid =>
                  val equalOrChild = p.equalOrChild(path)
                  val goodOwnership = owner == o 
                  logger.debug("Got grant %s > valid: %s, equalOrChild: %s, goodOwnership: %s".format(gid.take(10) + "...", valid, equalOrChild, goodOwnership))
                  valid && equalOrChild && goodOwnership
                }
              case _ => M.point(false)
            }.getOrElse { logger.debug("Could not locate grant " + gid); M.point(false) }
          )})
        })
      case ReducePermission =>
        if(owners.isEmpty) M.point(false)
        else forall( owners.map { owner =>
          exists( t.grants.map{ gid =>
            tokens.findGrant(gid).flatMap( _.map { 
              case g @ Grant(_, _, ReducePermission(p, o, _)) =>
                isValid(g).map { _ && p.equalOrChild(path) && owner == o }
              case _ => M.point(false)
            }.getOrElse(M.point(false))
          )})
        })
      case ModifyPermission =>
        if(owners.isEmpty) M.point(false)
        else forall(owners.map { owner =>
          exists(t.grants.map { gid =>
            tokens.findGrant(gid).flatMap( _.map {
              case g @ Grant(_, _, ModifyPermission(p, o, _)) =>
                isValid(g).map { _ && p.equalOrChild(path) && owner == o }
              case _ => M.point(false)
            }.getOrElse(M.point(false))
          )})
        })
      case TransformPermission =>
        if(owners.isEmpty) M.point(false)
        else forall(owners.map { owner =>
          exists(t.grants.map { gid =>
            tokens.findGrant(gid).flatMap( _.map { 
              case g @ Grant(_, _, TransformPermission(p, o, _)) =>
                isValid(g).map { _ && p.equalOrChild(path) && owner == o }
              case _ => M.point(false)
            }.getOrElse(M.point(false))
          )})
        })
    }
  }

  def isValid(grant: Grant): M[Boolean] = {
    (grant.issuer.map {
      tokens.findGrant(_).flatMap { _.map { parentGrant => 
        isValid(parentGrant).map { _ && grant.permission.accessType == parentGrant.permission.accessType }
      }.getOrElse { logger.warn("Could not locate issuer for grant: " + grant); M.point(false) } } 
    }.getOrElse { logger.debug("No issuer, parent grant == true"); M.point(true) }).map { _ && !grant.permission.isExpired(new DateTime()) }
  }
}

