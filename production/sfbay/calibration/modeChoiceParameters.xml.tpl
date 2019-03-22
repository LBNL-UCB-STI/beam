<?xml version="1.0" encoding="utf-8"?>
<modeChoices>
  <lccm>
    <name>Latent Class Choice Model</name>
    <parameters>lccm-long.csv</parameters>
  </lccm>
  <mnl>
    <name>Multinomial Logit</name>
    <parameters>
        <multinomialLogit name="mnl">
            <alternative name="car">
                <utility>
                    <param name="intercept" type="INTERCEPT">{{ mnl_car_intercept}}</param>
                    <param name="cost" type="MULTIPLIER">{{ mnl_car_cost }}</param>
                    <param name="time" type="MULTIPLIER">{{ mnl_car_time }}</param>
                </utility>
            </alternative>
            <alternative name="drive_transit">
                <utility>
                    <param name="intercept" type="INTERCEPT">{{ mnl_drive_transit_intercept }}</param>
                    <param name="cost" type="MULTIPLIER">{{ mnl_drive_transit_cost }}</param>
                    <param name="time" type="MULTIPLIER">{{ mnl_drive_transit_time }}</param>
                    <param name="transfer" type="MULTIPLIER">{{ mnl_drive_transit_transfer }}</param>
                </utility>
            </alternative>
            <alternative name="walk_transit">
                <utility>
                    <param name="intercept" type="INTERCEPT">{{ mnl_walk_transit_intercept }}</param>
                    <param name="cost" type="MULTIPLIER">{{ mnl_walk_transit_cost }}</param>
                    <param name="time" type="MULTIPLIER">{{ mnl_walk_transit_time }}</param>
                    <param name="transfer" type="MULTIPLIER">{{ mnl_walk_transit_transfer }}</param>
                </utility>
            </alternative>
            <alternative name="ride_hail">
                <utility>
                    <param name="intercept" type="INTERCEPT">{{ mnl_ride_hail_intercept }}</param>
                    <param name="cost" type="MULTIPLIER">{{ mnl_ride_hail_cost }}</param>
                    <param name="time" type="MULTIPLIER">{{ mnl_ride_hail_time }}</param>
                </utility>
            </alternative>
            <alternative name="walk">
                <utility>
                    <param name="intercept" type="INTERCEPT">{{ mnl_walk_intercept }}</param>
                    <param name="cost" type="MULTIPLIER">{{ mnl_walk_cost }}</param>
                    <param name="time" type="MULTIPLIER">{{ mnl_walk_time }}</param>
                </utility>
            </alternative>
            <alternative name="bike">
                <utility>
                    <param name="intercept" type="INTERCEPT">{{ mnl_bike_intercept }}</param>
                    <param name="cost" type="MULTIPLIER">{{ mnl_bike_cost }}</param>
                    <param name="time" type="MULTIPLIER">{{ mnl_bike_time }}</param>
                </utility>
            </alternative>
        </multinomialLogit>
    </parameters>
  </mnl>
</modeChoices>
