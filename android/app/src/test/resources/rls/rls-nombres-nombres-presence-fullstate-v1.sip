NOTIFY sip:mundokamailio1@172.30.30.40:6171 SIP/2.0
Via: SIP/2.0/UDP 172.30.30.11:5060;branch=z9hG4bKd244.2e3b9d57000000000000000000000000.0
To: <sip:mundokamailio1@172.30.30.11>;tag=86fa6dc345
From: <sip:rls@172.30.30.11>;tag=84ef00c017556515b71cbf013ae1c2ee-aae5580a
CSeq: 1 NOTIFY
Call-ID: 66bb98e25149085d
Content-Length: 1253
User-Agent: MundoPBX 2.0 (granja10)
Max-Forwards: 70
Event: presence
Contact: <sip:rls@172.30.30.11>
Subscription-State: active;expires=60
Require: eventlist
Content-Type: multipart/related;type="application/rlmi+xml";start="<1793069800.sip:rls@172.30.30.11.12108021>";boundary="DHlPtY5bvCBP54GrWwOUCJZB"

--DHlPtY5bvCBP54GrWwOUCJZB
Content-Transfer-Encoding: binary
Content-ID: <1793069800.sip:rls@172.30.30.11.12108021>
Content-Type: application/rlmi+xml;charset="UTF-8"

<?xml version="1.0" encoding="UTF-8"?>
<list uri="sip:rls@172.30.30.11" xmlns="urn:ietf:params:xml:ns:rlmi" version="1" fullState="true">
  <resource uri="sip:mundokamailio2@172.30.30.11">
    <name>(105) - Iván</name>
  </resource>
  <resource uri="sip:mundokamailio6@172.30.30.11">
    <name>(111) - Recepción &amp; Caja</name>
  </resource>
  <resource uri="sip:aiagent_lab@172.30.30.11">
    <name>aiagent_lab</name>
  </resource>
  <resource uri="sip:rem_ana@172.30.30.11">
    <name>Ana O'Neill</name>
  </resource>
  <resource uri="sip:comercial_demo@172.30.30.11">
    <name>comercial_demo</name>
  </resource>
  <resource uri="sip:mundokamailio7@172.30.30.11">
    <name>mundokamailio7</name>
  </resource>
  <resource uri="sip:rem_ohid@172.30.30.11">
    <name>Ohid &lt;"jefe"&gt;</name>
  </resource>
  <resource uri="sip:recepcion_demo@172.30.30.11">
    <name>recepcion_demo</name>
  </resource>
  <resource uri="sip:rem_xan@172.30.30.11">
    <name>rem_xan</name>
  </resource>
  <resource uri="sip:rem_yall@172.30.30.11"/>
</list>

--DHlPtY5bvCBP54GrWwOUCJZB--
