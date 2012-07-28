# salesfear

SalesFear aims to quell the insensate rage and terror which go hand in hand
with the Salesforce API.

It's a wrapper around http://blog.palominolabs.com/2011/03/03/a-new-java-salesforce-api-library/. 

Check http://clojars.org/salesfear for the latest version, then add to your
project.clj's dependencies.

[Their source](https://github.com/teamlazerbeez/sf-api-connector/tree/master/sf-rest-api-connector/src/main/java/com/teamlazerbeez/crm/sf/rest) will probably come in handy.

## Usage

Fire up a REPL and follow along!

``` clojure
(use 'salesfear.client)

; Connect to Salesforce with your credentials:
(salesforce! {:org-id "00DE0000000b894" :username "foo@bar.com" :password (str "mypw" "mytoken")})

; List all accounts.
(find :Account)

; Accounts are presented as immutable Maps. They implement lazerbees' SObject
interface, so you can pass them back to any internal method.
(first (find :Account))
#salesfear.client.CSObject{:type "Account", :id "001E000000Jx2nb", :CreatedDate "2012-07-26T01:12:20.000+0000", :Name "GenePoint", :Sic "3712", :Website "www.genepoint.com", :SLA__c "Bronze", :Description "Genomics company engaged in mapping and sequencing of the human genome and developing gene-based drugs", :ShippingPostalCode nil, :LastModifiedById "005E0000000Kr6TIAS", :BillingPostalCode nil, :SLASerialNumber__c "7324", :NumberOfEmployees "265", :AccountNumber "CC978213", :OwnerId "005E0000000Kr6TIAS", :ShippingState nil, :TickerSymbol nil, :BillingCity "Mountain View", :Type "Customer - Channel", :Site nil, :UpsellOpportunity__c "Yes", :Rating "Cold", :ShippingCity nil, :SLAExpirationDate__c "2012-02-21", :LastActivityDate nil, :BillingStreet "345 Shoreline Park\nMountain View, CA 94043\nUSA", :CustomerPriority__c "Low", :LastModifiedDate "2012-07-26T01:12:20.000+0000", :MasterRecordId nil, :ShippingCountry nil, :IsDeleted "false", :BillingCountry nil, :CreatedById "005E0000000Kr6TIAS", :AnnualRevenue "3.0E7", :Active__c "Yes", :NumberofLocations__c "1.0", :ParentId nil, :BillingState "CA", :Phone "(650) 867-3450", :Ownership "Private", :ShippingStreet "345 Shoreline Park\nMountain View, CA 94043\nUSA", :Fax "(650) 867-9895", :SystemModstamp "2012-07-26T01:12:20.000+0000", :Industry "Biotechnology"}

; Create an account. It'll return the ID.
(def id (create :Account {:Name "cat" :Site "the moon"}))
"001E000000JM7au"

; Now we'll get the record we created:
(:Name (get :Account id))
"foo"

; Making changes is easy
(update :Account id {:Name "a new name"})

(def account (get :Account id))
(select-keys account [:Name :Site])
{:Site "the moon", :Name "a new name"}

; And delete the record. Most functions take either [type id data] or [sobject].
(delete account)
:deleted

; Deletes are idempotent.
(delete :Account id)
:already-deleted

; 404s translate to nil.
(get :Account id)
nil

; SOQL queries
(create :Account {:Name "o'brien" :Site "1"})
(create :Account {:Name "o'brien" :Site "2"})
(query "select site from account where name = 'o\\'brien'")
(#salesfear.client.CSObject{:type "Account", :id "", :Site "1"} #salesfear.client.CSObject{:type "Account", :id "", :Site "2"})

; You can parameterize as well.
(query ["select site from ? where name = ?" :Account "o'brien"])

; For building queries, the full list of fields for a type might come in handy.
; Salesfear transparently caches this for each (salesforce ...) context.
(sobject-field-names :Account)
("Id" "IsDeleted" "MasterRecordId" "Name" "Type" "ParentId" "BillingStreet" "BillingCity" "BillingState" "BillingPostalCode" "BillingCountry" "ShippingStreet" "ShippingCity" "ShippingState" "ShippingPostalCode" "ShippingCountry" "Phone" "Fax" "AccountNumber" "Website" "Sic" "Industry" "AnnualRevenue" "NumberOfEmployees" "Ownership" "TickerSymbol" "Description" "Rating" "Site" "OwnerId" "CreatedDate" "CreatedById" "LastModifiedDate" "LastModifiedById" "SystemModstamp" "LastActivityDate" "CustomerPriority__c" "SLA__c" "Active__c" "NumberofLocations__c" "UpsellOpportunity__c" "SLASerialNumber__c" "SLAExpirationDate__c")

; Use find for basic equality constraints.
(find :Account {:Name "o'brien"})
(#salesfear.client.CSObject{:type "Account", :id "001E000000JM7c7", ...) ...)

; ffind finds a single object.
(ffind :Account {:Name "o'brien" :Site "1"})
#salesfear.client.CSObject{:type "Account", :id "001E000000JM7c2", ...

; Introspection. 
(describe-global)
(describe-sobject :Account)
; I wouldn't rely on these methods; there's too much I haven't looked at and
; clojurified. Consider (describe-global*) and friends, which return raw java
; objects.
```

I recommend using the (salesforce) macro to wrap all your work with a
particular SF connection pool. This binds the underlying connection pools to
dynamic variables *soap-pool*, *rest-pool*, etc in salesfear.client.

``` clojure
(salesforce {:org-id o :username u :password u}
  (create :Account ...)
  (get :Account ...)
  ...)
```

## License

Copyright © 2012 Kyle Kingsbury <aphyr@aphyr.com>

Distributed under the Eclipse Public License, the same as Clojure.
