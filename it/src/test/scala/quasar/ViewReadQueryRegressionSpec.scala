/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar

import slamdata.Predef._
import quasar.contrib.pathy.{ADir, APath}
import quasar.effect._
import quasar.fp._ , free._
import quasar.fs.{Empty, PhysicalError, ReadFile}
import quasar.fs.mount._, FileSystemDef.DefinitionResult
import quasar.main._
import quasar.regression._
import quasar.sql.{ScopedExpr, Sql}

import matryoshka.data.Fix
import pathy.Path._
import scalaz.{Failure => _, _}, Scalaz._
import scalaz.concurrent.Task
import scalaz.stream.Process

class ViewReadQueryRegressionSpec
  extends QueryRegressionTest[AnalyticalFileSystemIO](QueryRegressionTest.externalFS.map(_.take(1))) {

  val suiteName = "View Reads"
  type ViewFS[A] = (Mounting :\: ViewState :\: MonotonicSeq :/: AnalyticalFileSystemIO)#M[A]

  def mounts(path: APath, expr: Fix[Sql], vars: Variables): Task[Mounting ~> Task] =
    (
      TaskRef(Map[APath, MountConfig](path -> MountConfig.viewConfig(ScopedExpr(expr, Nil), vars))) |@|
      TaskRef(Empty.analyticalFileSystem[HierarchicalFsEffM]) |@|
      TaskRef(Mounts.empty[DefinitionResult[PhysFsEffM]])
    ) { (cfgsRef, hfsRef, mntdRef) =>
      val mnt =
        KvsMounter.interpreter[Task, PhysFsEff](
          KeyValueStore.impl.fromTaskRef(cfgsRef), hfsRef, mntdRef)

      foldMapNT(reflNT[Task] :+: Failure.toRuntimeError[Task, PhysicalError])
        .compose(mnt)
    }

  val seq = TaskRef(0L).map(MonotonicSeq.fromTaskRef)

  val RF = ReadFile.Ops[ReadFile]

  def queryResults(query: Fix[Sql], vars: Variables, basePath: ADir) = {
    val path = basePath </> file("view")
    val prg: Process[RF.unsafe.M, Data] = RF.scanAll(path)
    val interp = mounts(path, query, vars).flatMap(interpViews).unsafePerformSync

    def t: RF.unsafe.M ~> qfTransforms.CompExecM =
      new (RF.unsafe.M ~> qfTransforms.CompExecM) {
        def apply[A](fa: RF.unsafe.M[A]): qfTransforms.CompExecM[A] = {
          val u: ReadFile ~> Free[AnalyticalFileSystemIO, ?] =
            mapSNT(interp) compose view.readFile[ViewFS]

          EitherT(EitherT.right(WriterT.put(fa.run.flatMapSuspension(u))(Vector.empty)))
        }
      }

    prg.translate(t)
  }

  def interpViews(mnts: Mounting ~> Task): Task[ViewFS ~> AnalyticalFileSystemIO] =
    (ViewState.toTask(Map()) |@| seq)((v, s) =>
      (injectNT[Task, AnalyticalFileSystemIO] compose mnts) :+:
      (injectNT[Task, AnalyticalFileSystemIO] compose v) :+:
      (injectNT[Task, AnalyticalFileSystemIO] compose s) :+:
      reflNT[AnalyticalFileSystemIO])
}
