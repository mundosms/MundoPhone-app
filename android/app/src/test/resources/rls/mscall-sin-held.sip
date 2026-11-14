NOTIFY sip:mundokamailio7@172.30.22.40:6178 SIP/2.0
Via: SIP/2.0/UDP 172.30.22.11:5060;branch=z9hG4bK3dd6.b4e89092000000000000000000000000.0
To: <sip:mundokamailio7@172.30.22.11>;tag=2a73337cb6
From: <sip:mundokamailio1@172.30.22.11>;tag=cde849600d132998877c82004fcea57f-a1709f91
CSeq: 12 NOTIFY
Call-ID: 70bd48041afa9016
Content-Length: 1008
User-Agent: MundoPBX 2.0 (granja2)
Max-Forwards: 70
Event: presence
Contact: <sip:172.30.22.11>
Subscription-State: active;expires=573
Content-Type: application/pidf+xml

<?xml version="1.0" encoding="UTF-8"?>
<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" xmlns:ms="urn:mundosms:presence" entity="sip:mundokamailio1@172.30.22.11"><dm:person id="pcall"><rpid:activities><rpid:on-the-phone/></rpid:activities><dm:note>On the phone</dm:note></dm:person><tuple id="tcall"><status><basic>open</basic></status><contact>sip:mundokamailio1@172.30.22.11</contact><note>On the phone</note><ms:call-summary calls="1" ringing="0" talking="1" start="1793069085" limit="1793076285"/><ms:call id="1d653af0d186b7d0" dir="out" state="talking" start="1793069085" remote="mundokamailio2" maxseg="7200" limit="1793076285" lsrc="tarifa" answer="1793069085"/></tuple><tuple xmlns="urn:ietf:params:xml:ns:pidf" id="t1"><status><basic>open</basic></status></tuple>
  <tuple xmlns="urn:ietf:params:xml:ns:pidf" id="0x7fe16cf0c5b0">
    <status>
      <basic>open</basic>
    </status>
  </tuple>
</presence>
