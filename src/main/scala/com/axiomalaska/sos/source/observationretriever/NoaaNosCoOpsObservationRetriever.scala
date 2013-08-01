package com.axiomalaska.sos.source.observationretriever

import com.axiomalaska.sos.source.StationQuery
import scala.collection.mutable
import scala.collection.JavaConversions._
import org.apache.log4j.Logger
import com.axiomalaska.sos.source.SourceUrls
import com.axiomalaska.sos.source.data.LocalStation
import com.axiomalaska.sos.source.data.LocalSensor
import com.axiomalaska.sos.source.data.ObservationValues
import org.joda.time.DateTime
import com.axiomalaska.sos.source.data.LocalPhenomenon
import com.axiomalaska.sos.tools.HttpPart
import com.axiomalaska.sos.tools.HttpSender
import com.axiomalaska.sos.source.data.RawValues
import com.axiomalaska.phenomena.Phenomenon

class NoaaNosCoOpsObservationRetriever(private val stationQuery:StationQuery) 
	extends ObservationValuesCollectionRetriever {
  
  // ---------------------------------------------------------------------------
  // Private Data
  // ---------------------------------------------------------------------------
  
  private val LOGGER = Logger.getLogger(getClass())
  private val sosRawDataRetriever = new SosRawDataRetriever()

  // ---------------------------------------------------------------------------
  // ObservationValuesCollectionRetriever Members
  // ---------------------------------------------------------------------------

  def getObservationValues(station: LocalStation, sensor: LocalSensor,
    phenomenon: LocalPhenomenon, startDate: DateTime): List[ObservationValues] = {

    val sensorForeignId = sosRawDataRetriever.getSensorForeignId(phenomenon)
    val rawData = NoaaNosCoOpsObservationRetriever.getRawData(sensorForeignId, 
        station.databaseStation.foreign_tag, startDate, DateTime.now())

    val observationValuesCollection = createSensorObservationValuesCollection(
        station, sensor, phenomenon)
    val phenomenonForeignTags = observationValuesCollection.map(_.observedProperty.foreign_tag)

    for {
      rawValue <- NoaaNosCoOpsObservationRetriever.createRawValues(rawData, phenomenonForeignTags)
      observationValues <- observationValuesCollection.find(
          _.observedProperty.foreign_tag == rawValue.phenomenonForeignTag)
      (dateTime, value) <- rawValue.values
      if (dateTime.isAfter(startDate))
    } {
      observationValues.addValue(value, dateTime)
    }

    observationValuesCollection
  }

  // ---------------------------------------------------------------------------
  // Private Members
  // ---------------------------------------------------------------------------
  
  private def createSensorObservationValuesCollection(station: LocalStation, sensor: LocalSensor,
    phenomenon: LocalPhenomenon): List[ObservationValues] = {
    val observedProperties = stationQuery.getObservedProperties(
      station.databaseStation, sensor.databaseSensor, phenomenon.databasePhenomenon)

    for (observedProperty <- observedProperties) yield {
      new ObservationValues(observedProperty, sensor, phenomenon, observedProperty.foreign_units)
    }
  }
}

object NoaaNosCoOpsObservationRetriever{
	private val sosRawDataRetriever = new SosRawDataRetriever()
    
  /**
   * Get the raw unparsed string data for the station from the source
   */
  def getRawData(sensorForeignId:String, stationTag:String, 
      startDate:DateTime, endDate:DateTime):String = {
    
    val thirdyDaysOld = DateTime.now().minusDays(30)

    val adjustedStartDate = if (startDate.isBefore(thirdyDaysOld)) {
      thirdyDaysOld
    } else {
      startDate
    }
    
    sosRawDataRetriever.getRawData(SourceUrls.NOAA_NOS_CO_OPS_SOS, stationTag, 
        sensorForeignId, adjustedStartDate, endDate)
  }
  
  /**
   * Parse the raw unparsed data into DateTime, Value list. 
   * 
   * phenomenonForeignTag - The HADS Phenomenon Foreign Tag
   */
  def createRawValues(rawData: String, 
      phenomenonForeignTags: List[String]): List[RawValues] ={
    sosRawDataRetriever.createRawValues(rawData, phenomenonForeignTags)
  }
}