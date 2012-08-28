(defproject salesfear "0.1.1-SNAPSHOT"
  :description "Talk to the Salesforce API, via teamlazerbeez sf-api-connector."
  :url "http://github.com/aphyr/salesfear"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
            :dependencies [[org.clojure/clojure "1.3.0"]
                           [slingshot "0.10.3"]
                           [org.clojure/tools.logging "0.2.3"]
                           [clj-time "0.4.3"]
                           [com.teamlazerbeez/sf-rest-api-connector "1.0.0-SNAPSHOT"]
                           [com.teamlazerbeez/sf-soap-api-connector "1.0.0-SNAPSHOT"]])
