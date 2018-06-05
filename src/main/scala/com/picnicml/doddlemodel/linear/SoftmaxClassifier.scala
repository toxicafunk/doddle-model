package com.picnicml.doddlemodel.linear

import breeze.linalg.{*, DenseMatrix, View, argmax, convert, max, sum}
import breeze.numerics.{exp, log, pow}
import breeze.util.SerializableLogging
import com.picnicml.doddlemodel.data.{Features, RealVector, Simplex, Target}

/** An immutable multiple multinomial regression model with ridge regularization.
  *
  * @param lambda L2 regularization strength, must be positive, 0 means no regularization
  *
  * Examples:
  * val model = SoftmaxClassifier()
  * val model = SoftmaxClassifier(lambda = 1.5)
  */
@SerialVersionUID(1L)
class SoftmaxClassifier private (val lambda: Double, val numClasses: Option[Int], protected val w: Option[RealVector])
  extends LinearClassifier[SoftmaxClassifier] with Serializable with SerializableLogging {

  override protected[linear] def copy(numClasses: Int): SoftmaxClassifier = {
    if (numClasses == 2)
      logger.warn("Detected a binary classification problem, consider using the LogisticRegression model")
    new SoftmaxClassifier(this.lambda, Some(numClasses), this.w)
  }

  override protected def copy(w: RealVector): SoftmaxClassifier =
    new SoftmaxClassifier(this.lambda, this.numClasses, Some(w))

  override protected def predict(w: RealVector, x: Features): Target =
    convert(argmax(this.predictProba(w, x)(*, ::)), Double)

  override protected def predictProba(w: RealVector, x: Features): Simplex = {
    val numClasses = this.numClasses match {
      case Some(nc) => nc
      case None => throw new IllegalStateException("numClasses not set on a trained model")
    }

    val z = x * w.asDenseMatrix.reshape(x.cols, numClasses - 1, View.Require)
    val maxZ = max(z)
    val zExpPivot = DenseMatrix.horzcat(exp(z - maxZ), DenseMatrix.fill[Double](x.rows, 1)(exp(-maxZ)))
    zExpPivot(::, *) /:/ sum(zExpPivot(*, ::))
  }

  private var yPredProbaCache: Simplex = _
  private val wSlice: Range.Inclusive = 1 to -1

  override protected[linear] def loss(w: RealVector, x: Features, y: Target): Double = {
    val numClasses = this.numClasses match {
      case Some(nc) => nc
      case None => throw new IllegalStateException("numClasses must be set during training")
    }

    yPredProbaCache = this.predictProba(w, x)
    val yPredProbaOfTrueClass = 0 until x.rows map { rowIndex =>
      val targetClass = y(rowIndex).toInt
      yPredProbaCache(rowIndex, targetClass)
    }

    val wMatrix = w.asDenseMatrix.reshape(x.cols, numClasses - 1, View.Require)
    sum(log(DenseMatrix(yPredProbaOfTrueClass))) / (-x.rows.toDouble) +
      .5 * this.lambda * sum(pow(wMatrix(wSlice, ::), 2))
  }

  override protected[linear] def lossGrad(w: RealVector, x: Features, y: Target): RealVector = {
    val numClasses = this.numClasses match {
      case Some(nc) => nc
      case None => throw new IllegalStateException("numClasses must be set during training")
    }

    val yPredProba = yPredProbaCache(::, 0 to -2)

    val indicator = DenseMatrix.zeros[Double](yPredProba.rows, yPredProba.cols)
    0 until indicator.rows foreach { rowIndex =>
      val targetClass = y(rowIndex).toInt
      if (targetClass < numClasses - 1) indicator(rowIndex, targetClass) = 1.0
    }

    val grad = (x.t * (indicator - yPredProba)) / (-x.rows.toDouble)
    val wMatrix = w.asDenseMatrix.reshape(x.cols, numClasses - 1, View.Require)
    grad(wSlice, ::) += this.lambda * wMatrix(wSlice, ::)
    grad.toDenseVector
  }
}

object SoftmaxClassifier {

  def apply(): SoftmaxClassifier = new SoftmaxClassifier(0, None, None)

  def apply(lambda: Double): SoftmaxClassifier = {
    require(lambda > 0, "L2 regularization strength must be positive")
    new SoftmaxClassifier(lambda, None, None)
  }
}
