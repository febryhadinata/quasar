package quasar

import quasar.fp.free._
import scalaz._
import pathy.Path._

package object fs {
  type ReadFileF[A]   = Coyoneda[ReadFile, A]
  type WriteFileF[A]  = Coyoneda[WriteFile, A]
  type ManageFileF[A] = Coyoneda[ManageFile, A]
  type QueryFileF[A]  = Coyoneda[QueryFile, A]

  type FileSystem0[A] = Coproduct[WriteFileF, ManageFileF, A]
  type FileSystem1[A] = Coproduct[ReadFileF, FileSystem0, A]
  /** FileSystem[A] = [[ReadFileF]] \/ [[WriteFileF]] \/ [[ManageFileF]] \/ [[QueryFileF]] */
  type FileSystem[A]  = Coproduct[QueryFileF, FileSystem1, A]

  type ADir  = AbsDir[Sandboxed]
  type RDir  = RelDir[Sandboxed]
  type AFile = AbsFile[Sandboxed]
  type RFile = RelFile[Sandboxed]
  type APath = pathy.Path[Abs,_,Sandboxed]
  type RPath = pathy.Path[Rel,_,Sandboxed]

  type PathErr2T[F[_], A] = EitherT[F, PathError2, A]
  type FileSystemErrT[F[_], A] = EitherT[F, FileSystemError, A]

  def interpretFileSystem[M[_]: Functor](
    q: QueryFile ~> M,
    r: ReadFile ~> M,
    w: WriteFile ~> M,
    m: ManageFile ~> M
  ): FileSystem ~> M =
    interpret4[QueryFileF, ReadFileF, WriteFileF, ManageFileF, M](
      Coyoneda.liftTF(q), Coyoneda.liftTF(r), Coyoneda.liftTF(w), Coyoneda.liftTF(m))

  def convert(path: pathy.Path[_,_,Sandboxed]): fs.Path = fs.Path(posixCodec.printPath(path))
}

