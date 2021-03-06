package folding

import cats._
import scala.collection.GenTraversableOnce
import scala.language.higherKinds
import scala.language.existentials

sealed trait FoldW[A, B, W] { self =>

  def step: (W, A) => W
  def begin: W
  def done: W => B

  def map[C](f: B => C): FoldW[A, C, W] =
    FoldW[A, C, W](step, begin, f compose done)

  def ap[C, Z](f: FoldW[A , B => C, Z]): FoldW[A, C, (Z, W)] = {
    def step(x: (Z, W), a: A ): (Z, W) = (f.step(x._1, a), self.step(x._2, a))
    def begin: (Z, W) = (f.begin, self.begin)
    def done(x: (Z, W)): C = f.done(x._1)(self.done(x._2))

    FoldW(step, begin, done)
  }

  // foldr :: (a -> b -> b) -> b -> [a] -> b
  def fold[F[_]](fa: F[A])(implicit F: Foldable[F]): B =
    done(F.foldLeft(fa, begin)(step))

//  //(a -> b -> a) -> a -> [b] -> a
//  def fold(xs: List[A]): B = self.done(xs.foldLeft(begin)(step))

  def fold(xs: GenTraversableOnce[A]): B = self.done(xs.foldLeft(begin)(step))

  def foldMap(f: A => W)(implicit W: Monoid[W]): (W, B) => FoldW[A,B, W] = { (w, b) =>
    FoldW((w, a) => W.combine(w, f(a)), W.empty, done)
  }
}

object FoldW {
  def apply[A,B,W](s: (W, A) => W, b: W, d: W => B) = new FoldW[A,B, W] {
    override def step: (W, A) => W = s
    val begin: W = b
    override def done: (W) => B = d
  }

  def foldLeft[A, B](fa: FoldW[A, B, B])(xs: Seq[A]): B =
    xs.foldLeft(fa.begin)(fa.step)

  def premap[A, B, R, W](fr: FoldW[B, R, W])(f: A => B): FoldW[A, R, W] = {
    def step(w: W , a: A) = fr.step(w, f(a))

    FoldW(step, fr.begin, fr.done)
  }

}

final case class Fold[A, B](folder: FoldW[A, B, _]) {

  def map[C](f: B => C): Fold[A, C] = Fold(folder.map(f))
  def ap[C](f: => Fold[A, (B) => C]): Fold[A, C] = Fold(folder.ap(f.folder))
  def fold[F[_]](fa: F[A])(implicit F: Foldable[F]): B = folder.fold(fa)
  def fold(xs: GenTraversableOnce[A]): B = folder.fold(xs)

}

object Fold extends FoldInstances1 {
  def apply[A,B](z: B)(f: (B, A) => B): Fold[A, B] =
    Fold(FoldW[A, B, B](f, z, identity))


  def _Fold1[A](f: (A, A) => A): Fold[A, Option[A]] = {
    def step(mx: Option[A], a: A): Option[A] = Some {
      mx match {
        case None => a
        case Some(x) => f(x, a)
      }
    }

    Fold[A, Option[A]](None)(step)
  }

  def id[A](a: A): A = a

  def flip[A, B, C](f: (A, B) => C): (B, A) => C = (b, a) => f(a, b)

  def const[A, B](a: A, b: B): A = a

  def fold[F[_], A, B](f: Fold[A, B])(fa: F[A])(implicit F: Foldable[F]): B =
    f.fold(fa)

  def foldLeft[A, B](fa: Fold[A, B])(xs: GenTraversableOnce[A]): B =
    fa.fold(xs)

  def premap[A, B, R](fr: Fold[B, R])(f: A => B): Fold[A, R] =
    Fold(FoldW.premap(fr.folder)(f))

  // Convert a strict left 'Fold' into a scan
  def scan[A, B](fa: Fold[A, B])(xs: List[A]): List[B] =
    xs.foldLeft(Nil: List[B])((b, a) => fa.map[List[B]](b1 => b1 :: b).fold(List(a)))

  // Fold all values withing a container using 'combine' and 'empty on Monoid
  def mconcat[A](implicit M: Monoid[A]): Fold[A, A] = Fold(M.empty)(M.combine)

  // return a Fold implemented by mapping A values into B and then combining them using the given Monoid[B] instance.
  def foldMap[A, B](f: (A) => B)(implicit B: Monoid[B]): Fold[A,B] =
    Fold(B.empty)((b, a) => B.combine(f(a), b))

  // Get the first element of a container or return 'Nothing' if the container is empthy
  def head[A]: Fold[A, Option[A]] = _Fold1(const)

  // Get the last element of a container or return 'Nothing' if the container is empthy
  def last[A]: Fold[A, Option[A]] = _Fold1[A](flip(const))

  def any[A](pred: A => Boolean): Fold[A, Boolean] =
    Fold(false)((b, a) => b || pred(a))

  // Computes the sum of all elements
  def sum[A](implicit x: scala.math.Numeric[A]): Fold[A, A] =
    Fold(x.zero)(x.plus)

  // Computes the product of all elements
  def product[A](implicit x: scala.math.Numeric[A]): Fold[A, A] =
    Fold(x.one)(x.times)

  def maximum[A](implicit x: Ordering[A]): Fold[A, Option[A]] =
    _Fold1(x.max)

  def minimum[A](implicit x: Ordering[A]): Fold[A, Option[A]] =
    _Fold1(x.min)

  def elem[A](a: A)(implicit x: Eq[A]): Fold[A, Boolean] =
    any(x.eqv(a, _))

  def find[A](f: A => Boolean): Fold[A, Option[A]] =
    Fold(None: Option[A]){
      case (b @ Some(a), _) => b
      case (None, a)        => if (f(a)) Some(a) else None
    }

  def genericLength[A, B](implicit x: scala.math.Numeric[B]): Fold[A, B] =
    Fold(x.zero)((b, _) => x.plus(b, x.one))

  def length[A]: Fold[A, Long] = genericLength

}

sealed abstract class FoldInstances1 extends FoldInstances2 {
  implicit def foldApplicative[X]: Applicative[Fold[X, ?]] = new Applicative[Fold[X, ?]] {
    override def pure[A](x: A): Fold[X, A] = Fold(FoldW[X, A, Unit]({ case ((), _) => () }, (), _ => x))

    override def ap[A, B](fa: Fold[X, A])(f: Fold[X, (A) => B]): Fold[X,B] =  fa.ap(f)
  }
}

sealed abstract class FoldInstances2 extends FoldInstances3 {
  implicit def foldApply[X]: Apply[Fold[X, ?]] = new Apply[Fold[X, ?]] {
    override def ap[A, B](fa: Fold[X, A])(f: Fold[X, (A) => B]): Fold[X, B] = fa.ap(f)

    override def map[A, B](fa: Fold[X, A])(f: (A) => B): Fold[X, B] = fa map f

  }
}

sealed abstract class FoldInstances3 {
  implicit def foldFunctor[A]: Functor[Fold[A, ?]] = new Functor[Fold[A, ?]] {
    def map[C, D](fa: Fold[A, C])(f: (C) => D): Fold[A, D] =
      fa map f
  }

}