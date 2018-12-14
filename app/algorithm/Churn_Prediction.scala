package algorithm

/**
  * Created by hmaccelerate on 2018/12/1.
  */
/**
  * Created by hmaccelerate on 2018/12/1.
  */

import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.regression.DecisionTreeRegressor
import org.apache.spark.ml.classification.RandomForestClassifier
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object Churn_Prediction {
  def main(args: Array[String]) {

    val spark = SparkSession.builder.appName("Churn Prediction").config("spark.master", "local").getOrCreate()
    val sqlContext = spark.sqlContext
    import sqlContext.implicits._


//    Read the file from local
    val trains = spark.read.option("header","true").option("inferSchema","true").csv("E:\\DevData\\kkbox\\v2\\train_v2.csv").cache()
    val members = spark.read.option("header","true").option("inferSchema","true").csv("E:\\DevData\\kkbox\\members_v3.csv").cache()
    val transactions = spark.read.option("header","true").option("inferSchema","true").csv("E:\\DevData\\kkbox\\v2\\transactions_v2.csv")
    val user_logs = spark.read.option("header","true").option("inferSchema","true").csv("E:\\DevData\\kkbox\\v2\\user_logs_v2.csv").cache()

//    A.Feature engineering
//    1.Members Part
val regv_indexer = new StringIndexer().setInputCol("registered_via").setOutputCol("indexed_registered_via").fit(members)
    val indexed_regv_members= regv_indexer.transform(members).cache()

    val city_indexer = new StringIndexer().setInputCol("city").setOutputCol("indexed_city").fit(indexed_regv_members)
    val indexed_city_members= city_indexer.transform(indexed_regv_members).cache()


    indexed_city_members.show()

    // Use Decision Tree to process  outliers of 'bd' column in the members table
    val normal_members = indexed_city_members.where("bd between 1 and 100").cache()
    val outliers = indexed_city_members.where("bd == 0 OR bd< 0 OR bd> 100").cache()


    // assembler for combining raw features and features generated by different feature transformers into a single feature vector, in order to train ML models like logistic regression and decision trees.
    val assembler = new VectorAssembler().setInputCols(Array("indexed_city", "indexed_registered_via")).setOutputCol("city_via_features")

    // val output = assembler.transform(normal_members)

    //  Build decision tree
    val dt = new DecisionTreeRegressor().setLabelCol("bd").setFeaturesCol("city_via_features")

    val pipeline = new Pipeline().setStages(Array(assembler, dt))

    val dt_model = pipeline.fit(normal_members)

    // make prediction on bd
    val bd_pred = dt_model.transform(outliers)

    // combine the processed outliers and normal members
    val outlier_members = bd_pred.drop("city_via_features", "bd").withColumnRenamed("prediction", "bd").cache()
    val processed_members= outlier_members.unionAll(normal_members).cache()

    // 2.Transations Part
    // build the trans_count feature
    val trans_count = transactions.groupBy("msno").count()

    // build the is_discount feature
    def is_discount(plan_list_price: Int, actual_pay:Int):Int ={
      if (plan_list_price>actual_pay&&actual_pay!=0)
        return 1
      else
        return 0
    }

    // build the autorenew_&_not_cancel feature: the user is using autorenew and is not a cancelded user
    def is_autorenew_n_cancel(is_auto_renew:Int,is_cancel:Int):Int={
      if(is_auto_renew==1&&is_cancel==0)
        return 1
      else
        return 0
    }

    // build the notAutorenew&Cancel feature: the user is not using autorenew and is a cancelded user
    def is_n_autorenew_cancel(is_auto_renew:Int,is_cancel:Int):Int={
      if(is_auto_renew==0&&is_cancel==1)
        return 1
      else
        return 0
    }

    val new_df =transactions.map(x=> (x(0).toString,is_discount(x(3).toString.toInt,x(4).toString.toInt)
      ,is_autorenew_n_cancel(x(5).toString.toInt,x(8).toString.toInt)
      ,is_n_autorenew_cancel(x(5).toString.toInt,x(8).toString.toInt) )).toDF("msno","is_discount",
      "is_autorenew_n_cancel","is_n_autorenew_cancel")

      val join_all = transactions.join(new_df,"msno")
        .join(trans_count,"msno")

    //Encode columns of transactions
    val payid_indexer = new StringIndexer().setInputCol("payment_method_id").setOutputCol("indexed_payment_method_id").fit(join_all)
    val final_transactions = payid_indexer.transform(join_all)

//    write processed transactions into the csv file
    try{
      final_transactions.coalesce(1).write.option("header", "true").csv("E:\\DevData\\kkbox\\final_transactions.csv")

    }catch{
      case e1:IllegalArgumentException=> print("fail to save the data into csv:"+e1)
      case e2:RuntimeException => print("fail to save the data into csv:"+e2)
      case e3: Exception =>print("fail to save the data into csv:"+e3)
    }

    // 3.User_logs Part
    val final_user_logs = user_logs.groupBy("msno").agg(sum("num_25"),sum("num_50"),sum("num_75"),sum("num_985"),
      sum("num_100"),sum("num_unq"),sum("total_secs")).cache()

//    write the processed user logs into csv file
    try{
      final_user_logs.coalesce(1).write.option("header", "true").csv("E:\\DevData\\kkbox\\final_user_logs.csv")

    }catch{
      case e1:IllegalArgumentException=> print("fail to save the data into csv:"+e1)
      case e2:RuntimeException => print("fail to save the data into csv:"+e2)
      case e3: Exception =>print("fail to save the data into csv:"+e3)
    }


    // 4.Join every different parts dataframe together to build final training dataset
    val trains_df = trains.join(processed_members,"msno")
      .join(final_transactions,"msno").join(final_user_logs,"msno")
      .drop("gender","registered_via","city","registration_init_time").cache()

//    write processed training dataset into csv file
    try{
      trains_df.coalesce(1).write.option("header", "true").csv("E:\\DevData\\kkbox\\trains_df.csv")

    }catch{
      case e1:IllegalArgumentException=> print("fail to save the data into csv:"+e1)
      case e2:RuntimeException => print("fail to save the data into csv:"+e2)
      case e3: Exception =>print("fail to save the data into csv:"+e3)
    }

    //5.Splitting the data in train and test set
    val Array(training_set, testing_set) = trains_df.randomSplit(Array(0.8, 0.2))

    //Convert columns to feature vector
    val assembler_train = new VectorAssembler().setInputCols(Array("is_discount","is_autorenew_n_cancel","is_n_autorenew_cancel","count"
      ,"sum(num_25)","sum(num_50)","sum(num_75)","sum(num_985)","sum(num_100)","sum(num_unq)","sum(total_secs)"
      ,"indexed_registered_via","indexed_city","indexed_payment_method_id","bd")).setOutputCol("features")



    // B. Build the Random Forest Classifier
    val rf = new RandomForestClassifier().setLabelCol("is_churn").setFeaturesCol("features").setNumTrees(15).setMaxDepth(4).setMaxBins(39)


    //Build pipeline for Random Forest Classifier
    val pipeline_rf = new Pipeline().setStages(Array(assembler_train,rf))

    //Build evaluator for Random Forest Classifier
    val evaluator = new MulticlassClassificationEvaluator().setLabelCol("is_churn")

    val model_rf = pipeline_rf.fit(training_set)

//    save the trained model into csv file
        try{
          model_rf.save("E:\\DevData\\kkbox\\model_rf")
        }catch{
          case e1:IllegalArgumentException=> print("fail to save the data into csv:"+e1)
          case e2:RuntimeException => print("fail to save the data into csv:"+e2)
          case e3: Exception =>print("fail to save the data into csv:"+e3)
        }

    //Apply test data on the model
    val result_rf = model_rf.transform(testing_set)
    val result = result_rf.drop("features").drop("probability").drop("rawPrediction")
    //    result_rf.show(10)

//    write the prediction result into csv file
    try{
      //      repartition (preferred if upstream data is large, but requires a shuffle)
      //      coalesce(1)(can use when fit all the data into RAM on one worker thus)
      result.repartition(1).write.format("com.databricks.spark.csv").option("header", "true").save("E:\\DevData\\kkbox\\result_rf.csv")
    }catch{
      case e1:IllegalArgumentException=> print("fail to save the data into csv:"+e1)
      case e2:RuntimeException => print("fail to save the data into csv:"+e2)
      case e3: Exception =>print("fail to save the data into csv:"+e3)
    }


    //Select predication and label columns
    val predictionAndLabels_rf = result_rf.select("rawPrediction","prediction", "is_churn")


    // Evaluate the performance
    println("Evaluation of Random Forest model without dimensionality reduction")
    val evaluator_rf = new MulticlassClassificationEvaluator().setMetricName("weightedPrecision").setLabelCol("is_churn")
    println("Precision:" + evaluator_rf.evaluate(predictionAndLabels_rf))
    //    Precision:0.9070628309125287

    val evaluator1_rf = new MulticlassClassificationEvaluator().setMetricName("weightedRecall").setLabelCol("is_churn")
    println("Recall:" + evaluator1_rf.evaluate(predictionAndLabels_rf))
    //    Recall:0.8986020617028558

  }
}