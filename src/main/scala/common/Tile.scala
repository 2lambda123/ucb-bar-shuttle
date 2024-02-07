package shuttle.common

import chisel3._
import chisel3.util._

import scala.collection.mutable.{ListBuffer}

import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.{RocketCrossingParams}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.prci.ClockSinkParameters

import shuttle.ifu._
import shuttle.exu._
import shuttle.dmem._

case class ShuttleTileParams(
  core: ShuttleCoreParams = ShuttleCoreParams(),
  icache: Option[ICacheParams] = Some(ICacheParams(prefetch=true)),
  dcacheParams: ShuttleDCacheParams = ShuttleDCacheParams(),
  trace: Boolean = false,
  name: Option[String] = Some("shuttle_tile"),
  btb: Option[BTBParams] = Some(BTBParams()),
  tileId: Int = 0) extends InstantiableTileParams[ShuttleTile]
{
  require(icache.isDefined)
  def instantiate(crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters): ShuttleTile = {
    new ShuttleTile(this, crossing, lookup)
  }

  val beuAddr: Option[BigInt] = None
  val blockerCtrlAddr: Option[BigInt] = None
  val dcache = Some(DCacheParams(rowBits=64, nSets=dcacheParams.nSets, nWays=dcacheParams.nWays, nMSHRs=dcacheParams.nMSHRs))
  val boundaryBuffers: Boolean = false // if synthesized with hierarchical PnR, cut feed-throughs?
  val clockSinkParams: ClockSinkParameters = ClockSinkParameters()
  val baseName = name.getOrElse("shuttle_tile")
  val uniqueName = s"${baseName}_$tileId"
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
    with HasTileParameters
    with SinksExternalInterrupts
    with SourcesExternalNotifications
{
  // Private constructor ensures altered LazyModule.p is used implicitly
  def this(params: ShuttleTileParams, crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = None
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

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(tileId))
  }
  val roccs = p(BuildRoCC).map(_(p))

  roccs.map(_.atlNode).foreach { atl => tlMasterXbar.node :=* atl }
  roccs.map(_.tlNode).foreach { tl => tlOtherMastersNode :=* tl }
  val roccCSRs = roccs.map(_.roccCSRs)
  require(roccCSRs.flatten.map(_.id).toSet.size == roccCSRs.flatten.size,
    "LazyRoCC instantiations require overlapping CSRs")

  val frontend = LazyModule(new ShuttleFrontend(tileParams.icache.get, tileId))
  tlMasterXbar.node := TLBuffer() := TLWidthWidget(tileParams.icache.get.fetchBytes) := frontend.masterNode
  frontend.resetVectorSinkNode := resetVectorNexusNode

  val nPTWPorts = 2+ roccs.map(_.nPTWPorts).sum

  val dcache = LazyModule(new ShuttleDCache(tileId, ShuttleDCacheParams())(p))
  tlMasterXbar.node := TLBuffer() := TLWidthWidget(tileParams.dcache.get.rowBits/8) := dcache.node

  override lazy val module = new ShuttleTileModuleImp(this)
}

class ShuttleTileModuleImp(outer: ShuttleTile) extends BaseTileModuleImp(outer)
{
  val core = Module(new ShuttleCore(outer, outer.dcache.module.edge)(outer.p))

  val dcachePorts = Wire(Vec(2, new ShuttleDCacheIO))
  val ptwPorts = Wire(Vec(outer.nPTWPorts, new TLBPTWIO))
  val edge = outer.dcache.node.edges.out(0)
  ptwPorts(0) <> core.io.ptw_tlb
  ptwPorts(1) <> outer.frontend.module.io.ptw

  val ptw = Module(new PTW(outer.nPTWPorts)(edge, outer.p))
  if (outer.usingPTW) {
    dcachePorts(0).req.valid := ptw.io.mem.req.valid
    dcachePorts(0).req.bits.addr := ptw.io.mem.req.bits.addr
    dcachePorts(0).req.bits.tag := ptw.io.mem.req.bits.tag
    dcachePorts(0).req.bits.cmd := ptw.io.mem.req.bits.cmd
    dcachePorts(0).req.bits.size := ptw.io.mem.req.bits.size
    dcachePorts(0).req.bits.signed := false.B
    dcachePorts(0).req.bits.data := 0.U
    dcachePorts(0).req.bits.mask := 0.U
    ptw.io.mem.req.ready := dcachePorts(0).req.ready

    dcachePorts(0).s1_paddr := RegEnable(ptw.io.mem.req.bits.addr, ptw.io.mem.req.valid)
    dcachePorts(0).s1_kill := ptw.io.mem.s1_kill
    dcachePorts(0).s1_data := ptw.io.mem.s1_data
    ptw.io.mem.s2_nack := dcachePorts(0).s2_nack
    dcachePorts(0).s2_kill := ptw.io.mem.s2_kill

    ptw.io.mem.resp.valid := dcachePorts(0).resp.valid
    ptw.io.mem.resp.bits := DontCare
    ptw.io.mem.resp.bits.has_data := true.B
    ptw.io.mem.resp.bits.tag := dcachePorts(0).resp.bits.tag
    ptw.io.mem.resp.bits.data := dcachePorts(0).resp.bits.data
    ptw.io.mem.resp.bits.size := dcachePorts(0).resp.bits.size
    ptw.io.mem.ordered := dcachePorts(0).ordered
    dcachePorts(0).keep_clock_enabled := ptw.io.mem.keep_clock_enabled
    ptw.io.mem.clock_enabled := dcachePorts(0).clock_enabled
    ptw.io.mem.perf := dcachePorts(0).perf
    ptw.io.mem.s2_nack_cause_raw := false.B
    ptw.io.mem.s2_uncached := false.B
    ptw.io.mem.replay_next := false.B
    ptw.io.mem.s2_gpa := false.B
    ptw.io.mem.s2_gpa_is_pte := false.B

    val ptw_s2_addr = Pipe(ptw.io.mem.req.fire, ptw.io.mem.req.bits.addr, 2).bits
    val ptw_s2_legal = edge.manager.findSafe(ptw_s2_addr).reduce(_||_)
    ptw.io.mem.s2_paddr := ptw_s2_addr
    ptw.io.mem.s2_xcpt.ae.ld := !(ptw_s2_legal &&
      edge.manager.fastProperty(ptw_s2_addr, p => TransferSizes.asBool(p.supportsGet), (b: Boolean) => b.B))
    ptw.io.mem.s2_xcpt.ae.st := false.B
    ptw.io.mem.s2_xcpt.pf.ld := false.B
    ptw.io.mem.s2_xcpt.pf.st := false.B
    ptw.io.mem.s2_xcpt.gf.ld := false.B
    ptw.io.mem.s2_xcpt.gf.st := false.B
    ptw.io.mem.s2_xcpt.ma.ld := false.B
    ptw.io.mem.s2_xcpt.ma.st := false.B
  }

  outer.decodeCoreInterrupts(core.io.interrupts) // Decode the interrupt vector

  // Pass through various external constants and reports that were bundle-bridged into the tile
  outer.traceSourceNode.bundle <> core.io.trace
  core.io.hartid := outer.hartIdSinkNode.bundle
  require(core.io.hartid.getWidth >= outer.hartIdSinkNode.bundle.getWidth,
    s"core hartid wire (${core.io.hartid.getWidth}) truncates external hartid wire (${outer.hartIdSinkNode.bundle.getWidth}b)")

  // Connect the core pipeline to other intra-tile modules
  outer.frontend.module.io.cpu <> core.io.imem
  dcachePorts(1) <> core.io.dmem // TODO outer.dcachePorts += () => module.core.io.dmem ??

  // Connect the coprocessor interfaces
  core.io.rocc := DontCare
  if (outer.roccs.size > 0) {
    val (respArb, cmdRouter) = {
      val respArb = Module(new RRArbiter(new RoCCResponse()(outer.p), outer.roccs.size))
      val cmdRouter = Module(new RoccCommandRouter(outer.roccs.map(_.opcodes))(outer.p))
      outer.roccs.zipWithIndex.foreach { case (rocc, i) =>
        ptwPorts(i+2) <> rocc.module.io.ptw
        rocc.module.io.cmd <> cmdRouter.io.out(i)
        rocc.module.io.mem := DontCare
        rocc.module.io.mem.req.ready := false.B
        assert(!rocc.module.io.mem.req.valid)
        respArb.io.in(i) <> Queue(rocc.module.io.resp)
      }
      val nFPUPorts = outer.roccs.count(_.usesFPU)
      if (nFPUPorts > 0) {
        val fpu = Module(new FPU(outer.tileParams.core.fpu.get)(outer.p))
        fpu.io := DontCare
        fpu.io.fcsr_rm := core.io.fcsr_rm
        fpu.io.ll_resp_val := false.B
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
    val roccCSRIOs = outer.roccs.map(_.module.io.csrs)
    (core.io.rocc.csrs zip roccCSRIOs.flatten).foreach { t => t._2 := t._1 }
  }


  val dcacheArb = Module(new ShuttleDCacheArbiter(2)(outer.p))
  outer.dcache.module.io <> dcacheArb.io.mem

  core.io.ptw <> ptw.io.dpath

  dcacheArb.io.requestor <> dcachePorts
  ptw.io.requestor <> ptwPorts
}
