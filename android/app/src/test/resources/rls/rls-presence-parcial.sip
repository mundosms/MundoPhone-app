NOTIFY sip:mundokamailio1@172.30.22.40:6171 SIP/2.0
Via: SIP/2.0/UDP 172.30.22.11:5060;branch=z9hG4bK7fa7.305fa955000000000000000000000000.0
To: <sip:mundokamailio1@172.30.22.11>;tag=7c1f9914db
From: <sip:rls@172.30.22.11>;tag=cde849600d132998877c82004fcea57f-07fe4256
CSeq: 3 NOTIFY
Call-ID: 01721d218d26c50a
Content-Length: 2756
User-Agent: MundoPBX 2.0 (granja2)
Max-Forwards: 70
Event: presence
Contact: <sip:rls@172.30.22.11>
Subscription-State: active;expires=596
Require: eventlist
Content-Type: multipart/related;type="application/rlmi+xml";start="<1793064812.sip:rls@172.30.22.11.311832764>";boundary="mKFaiTvORgAtnfFnmSYHTKP3"

--mKFaiTvORgAtnfFnmSYHTKP3
Content-Transfer-Encoding: binary
Content-ID: <1793064812.sip:rls@172.30.22.11.311832764>
Content-Type: application/rlmi+xml;charset="UTF-8"

<?xml version="1.0"?>
<list uri="sip:rls@172.30.22.11" xmlns="urn:ietf:params:xml:ns:rlmi" version="3" fullState="false"><resource uri="sip:mundokamailio2@172.30.22.11"><instance id="Scf8UhwQ" state="active" cid="1793064812.sip:mundokamailio2@172.30.22.11.534606130"/></resource><resource uri="sip:mundokamailio6@172.30.22.11"><instance id="Scf8UhwQ" state="active" cid="1793064812.sip:mundokamailio6@172.30.22.11.264481421"/></resource></list>

--mKFaiTvORgAtnfFnmSYHTKP3
Content-Transfer-Encoding: binary
Content-ID: <1793064812.sip:mundokamailio2@172.30.22.11.534606130>
Content-Type: application/pidf+xml

<?xml version="1.0" encoding="UTF-8"?>
<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" xmlns:ms="urn:mundosms:presence" entity="sip:mundokamailio2@172.30.22.11"><dm:person id="pcall"><rpid:activities><rpid:on-the-phone/></rpid:activities><dm:note>On the phone: mundokamailio6</dm:note></dm:person><tuple id="tcall"><status><basic>open</basic></status><contact>sip:mundokamailio2@172.30.22.11</contact><note>On the phone</note><ms:call-summary calls="1" ringing="0" talking="1" start="1793064811" limit="1793072011"/><ms:call id="f70a3232a3e27a70" dir="out" state="talking" start="1793064811" remote="mundokamailio6" maxseg="7200" limit="1793072011" lsrc="tarifa"/></tuple>
  <tuple xmlns="urn:ietf:params:xml:ns:pidf" id="0x7f93429997a0">
    <status>
      <basic>open</basic>
    </status>
  </tuple>
</presence>

--mKFaiTvORgAtnfFnmSYHTKP3
Content-Transfer-Encoding: binary
Content-ID: <1793064812.sip:mundokamailio6@172.30.22.11.264481421>
Content-Type: application/pidf+xml

<?xml version="1.0" encoding="UTF-8"?>
<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" xmlns:ms="urn:mundosms:presence" entity="sip:mundokamailio6@172.30.22.11"><dm:person id="pcall"><rpid:activities><rpid:on-the-phone/></rpid:activities><dm:note>Ringing: mundokamailio2</dm:note></dm:person><tuple id="tcall"><status><basic>open</basic></status><contact>sip:mundokamailio6@172.30.22.11</contact><note>Ringing</note><ms:call-summary calls="1" ringing="1" talking="0" start="1793064811"/><ms:call id="18df436e-3b8a-4b9e-8e6d-abf81dd08120" dir="in" state="ringing" start="1793064811" remote="mundokamailio2"/></tuple>
  <tuple xmlns="urn:ietf:params:xml:ns:pidf" id="0x7f93410a3e70">
    <status>
      <basic>open</basic>
    </status>
  </tuple>
</presence>

--mKFaiTvORgAtnfFnmSYHTKP3--
