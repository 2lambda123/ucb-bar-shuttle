package shuttle.common

import chisel3._
import chisel3.util.{RRArbiter, Queue}

import scala.collection.mutable.{ListBuffer}

import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{LogicalTreeNode }
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.{RocketCrossingParams}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.prci.ClockSinkParameters

import testchipip.{ExtendedTracedInstruction, WithExtendedTraceport}

import shuttle.ifu._
import shuttle.exu._

case class ShuttleTileParams(
  core: ShuttleCoreParams = ShuttleCoreParams(),
  icache: Option[ICacheParams] = Some(ICacheParams(prefetch=true)),
  dcache: Option[DCacheParams] = Some(DCacheParams()),
  trace: Boolean = false,
  name: Option[String] = Some("shuttle_tile"),
  btb: Option[BTBParams] = Some(BTBParams()),
  hartId: Int = 0) extends InstantiableTileParams[ShuttleTile]
{
  require(icache.isDefined)
  require(dcache.isDefined)
  def instantiate(crossing: TileCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters): ShuttleTile = {
    new ShuttleTile(this, crossing, lookup)
  }

  val beuAddr: Option[BigInt] = None
  val blockerCtrlAddr: Option[BigInt] = None
  val boundaryBuffers: Boolean = false // if synthesized with hierarchical PnR, cut feed-throughs?
  val clockSinkParams: ClockSinkParameters = ClockSinkParameters()
}


case class ShuttleTileAttachParams(
  tileParams: ShuttleTileParams,
  crossingParams: RocketCrossingParams
) extends CanAttachTile {
  type TileType = ShuttleTile
  val lookup = PriorityMuxHartIdFromSeq(Seq(tileParams))
}

class ShuttleTile private(
  val shuttleParams: ShuttleTileParams,
  crossing: ClockCrossingType,
  lookup: LookupByHartIdImpl,
  q: Parameters)
    extends BaseTile(shuttleParams, crossing, lookup, q)
    with HasLazyRoCC
    with SinksExternalInterrupts
    with SourcesExternalNotifications
    with WithExtendedTraceport
{
  // Private constructor ensures altered LazyModule.p is used implicitly
  def this(params: ShuttleTileParams, crossing: TileCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = IntIdentityNode()
  val masterNode = visibilityNode
  val slaveNode = TLIdentityNode()

  tlOtherMastersNode := TLBuffer() := tlMasterXbar.node
  masterNode :=* tlOtherMastersNode


  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("ucb-bar,shuttle", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++
        cpuProperties ++
        nextLevelCacheProperty ++
        tileProperties)
    }
  }

  val frontend = LazyModule(new ShuttleFrontend(tileParams.icache.get, staticIdForMetadataUseOnly))
  tlMasterXbar.node := TLBuffer() := TLWidthWidget(tileParams.icache.get.fetchBytes) := frontend.masterNode
  frontend.resetVectorSinkNode := resetVectorNexusNode
  nPTWPorts += 1

  nDCachePorts += 1 /* core */

  override lazy val module = new ShuttleTileModuleImp(this)
}

class ShuttleTileModuleImp(outer: ShuttleTile) extends BaseTileModuleImp(outer)
  with CanHavePTWModule
{
  ptwPorts += outer.frontend.module.io.ptw

  val core = Module(new ShuttleCore(outer)(outer.p))
  outer.decodeCoreInterrupts(core.io.interrupts) // Decode the interrupt vector

  // Pass through various external constants and reports that were bundle-bridged into the tile
  outer.traceSourceNode.bundle <> core.io.trace
  core.io.hartid := outer.hartIdSinkNode.bundle
  require(core.io.hartid.getWidth >= outer.hartIdSinkNode.bundle.getWidth,
    s"core hartid wire (${core.io.hartid.getWidth}) truncates external hartid wire (${outer.hartIdSinkNode.bundle.getWidth}b)")

  // Connect the core pipeline to other intra-tile modules
  outer.frontend.module.io.cpu <> core.io.imem
  dcachePorts += core.io.dmem // TODO outer.dcachePorts += () => module.core.io.dmem ??
  core.io.ptw <> ptw.io.dpath

  // Connect the coprocessor interfaces
  core.io.rocc := DontCare
  if (outer.roccs.size > 0) {
    val (respArb, cmdRouter) = {
      val respArb = Module(new RRArbiter(new RoCCResponse()(outer.p), outer.roccs.size))
      val cmdRouter = Module(new RoccCommandRouter(outer.roccs.map(_.opcodes))(outer.p))
      outer.roccs.zipWithIndex.foreach { case (rocc, i) =>
        rocc.module.io.ptw ++=: ptwPorts
        rocc.module.io.cmd <> cmdRouter.io.out(i)
        val dcIF = Module(new SimpleHellaCacheIF()(outer.p))
        dcIF.io.requestor <> rocc.module.io.mem
        dcachePorts += dcIF.io.cache
        respArb.io.in(i) <> Queue(rocc.module.io.resp)
      }
      val nFPUPorts = outer.roccs.count(_.usesFPU)
      if (nFPUPorts > 0) {
        val fpu = Module(new FPU(outer.tileParams.core.fpu.get)(outer.p))
        fpu.io := DontCare
        fpu.io.fcsr_rm := core.io.fcsr_rm
        fpu.io.dmem_resp_val := false.B
        fpu.io.valid := false.B
        fpu.io.killx := false.B
        fpu.io.killm := false.B

        val fpArb = Module(new InOrderArbiter(new FPInput()(outer.p), new FPResult()(outer.p), nFPUPorts))
        val fp_rocc_ios = outer.roccs.filter(_.usesFPU).map(_.module.io)
        fpArb.io.in_req <> fp_rocc_ios.map(_.fpu_req)
        fp_rocc_ios.zip(fpArb.io.in_resp).foreach {
          case (rocc, arb) => rocc.fpu_resp <> arb
        }
        fpu.io.cp_req <> fpArb.io.out_req
        fpArb.io.out_resp <> fpu.io.cp_resp
      }
      (respArb, cmdRouter)
    }

    cmdRouter.io.in <> core.io.rocc.cmd
    outer.roccs.foreach(_.module.io.exception := core.io.rocc.exception)
    core.io.rocc.resp <> respArb.io.out
    core.io.rocc.busy <> (cmdRouter.io.busy || outer.roccs.map(_.module.io.busy).reduce(_ || _))
    core.io.rocc.interrupt := outer.roccs.map(_.module.io.interrupt).reduce(_ || _)
  }

  // TODO eliminate this redundancy
  val h = dcachePorts.size
  val c = core.dcacheArbPorts
  val o = outer.nDCachePorts
  require(h == c, s"port list size was $h, core expected $c")
  require(h == o, s"port list size was $h, outer counted $o")
  // TODO figure out how to move the below into their respective mix-ins
  dcacheArb.io.requestor <> dcachePorts
  ptw.io.requestor <> ptwPorts
}
