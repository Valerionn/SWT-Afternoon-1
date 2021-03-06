package at.tugraz.ist.sw20.swta1.cheat.ui.main

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.view.get
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import at.tugraz.ist.sw20.swta1.cheat.ChatActivity
import at.tugraz.ist.sw20.swta1.cheat.R
import at.tugraz.ist.sw20.swta1.cheat.RecyclerItemClickListener
import at.tugraz.ist.sw20.swta1.cheat.bluetooth.*
import at.tugraz.ist.sw20.swta1.cheat.ui.main.adapters.BluetoothDeviceAdapter
import kotlinx.android.synthetic.main.item_title_cell.view.*
import java.util.*
import kotlin.concurrent.schedule


class MainFragment : Fragment() {

    companion object {
        fun newInstance() = MainFragment()
    }

    private lateinit var viewModel: MainViewModel

    lateinit var lvPairedDevices: RecyclerView
    lateinit var lvNearbyDevices: RecyclerView
    lateinit var pullToRefreshContainer: SwipeRefreshLayout
    @Volatile var currentConnectingIndicator: ProgressBar? = null

    private val REQUEST_ENABLE_BLUETOOTH: Int = 1
    private var bluetoothAdapter: BluetoothAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            // Device doesn't support bluetooth
            BluetoothServiceProvider.useMock = true
            Toast.makeText(activity, getString(R.string.no_bluetooth), Toast.LENGTH_SHORT).show()
        } else {
            if (!bluetoothAdapter!!.isEnabled) {
                val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBluetoothIntent, REQUEST_ENABLE_BLUETOOTH)
            }
        }
        BluetoothServiceProvider.getBluetoothService().setDiscoverable(context!!)
        val root = inflater.inflate(R.layout.main_fragment, container, false)
        lvPairedDevices = root.findViewById(R.id.list_paired_devices)
        lvNearbyDevices = root.findViewById(R.id.list_nearby_devices)

        val titlePaired: View = root.findViewById(R.id.title_paired_devices)
        val titleNearby: View = root.findViewById(R.id.title_nearby_devices)

        titlePaired.title.text = getString(R.string.paired_devices)
        titleNearby.title.text = getString(R.string.nearby_devices)

        lvPairedDevices.layoutManager = LinearLayoutManager(this.context)
        lvPairedDevices.isNestedScrollingEnabled = false
        lvPairedDevices.setHasFixedSize(true)
        lvNearbyDevices.layoutManager = LinearLayoutManager(this.context)
        lvNearbyDevices.isNestedScrollingEnabled = false
        lvNearbyDevices.setHasFixedSize(true)

        pullToRefreshContainer = root.findViewById<SwipeRefreshLayout>(R.id.pull_to_refresh_container)
        pullToRefreshContainer.setOnRefreshListener {
            BluetoothServiceProvider.getBluetoothService().setDiscoverable(context!!)
            viewModel.nearbyDevices.observe(viewLifecycleOwner, Observer { deviceList ->
                BluetoothServiceProvider.getBluetoothService().discoverDevices({ device ->
                    if (deviceList.find { d -> d.address == device.address } == null) {
                        Log.println(Log.INFO, "Found Nearby Device: ", device.name)
                        deviceList.add(RealBluetoothDevice(device))
                        val adapterNearby = BluetoothDeviceAdapter(this.context!!, deviceList)
                        lvNearbyDevices.adapter = adapterNearby
                        lvNearbyDevices.addOnItemTouchListener(RecyclerItemClickListener(context, lvNearbyDevices, object : RecyclerItemClickListener.OnItemClickListener {
                            override fun onItemClick(view: View?, position: Int) {
                                connectToSelectedDevice(
                                    adapterNearby.getDeviceAt(position),
                                    lvNearbyDevices[position].findViewById<ProgressBar>(R.id.loading_spinner)
                                )
                            }
        
                            override fun onLongItemClick(view: View?, position: Int) {}
                        }))
                    }
                }, {
                    pullToRefreshContainer.isRefreshing = false
                })
            })

            Timer().schedule(10000) {
                pullToRefreshContainer.isRefreshing = false
            }
        }

        return root
    }

    override fun onResume() {
        super.onResume()

        synchronized(this) {
            activity!!.runOnUiThread {
                currentConnectingIndicator?.visibility = View.GONE
                currentConnectingIndicator = null
            }
        }

        BluetoothServiceProvider.getBluetoothService().setOnStateChangeListener { oldState, newState ->
            if (newState == BluetoothState.CONNECTED) {
                val intent = Intent(activity, ChatActivity::class.java)
                context!!.startActivity(intent)
            } else if(newState == BluetoothState.READY) {
                synchronized(this) {
                    activity!!.runOnUiThread {
                        currentConnectingIndicator?.visibility = View.GONE
                        currentConnectingIndicator = null
                    }
                }

                if(oldState == BluetoothState.ATTEMPT_CONNECTION) {
                    activity!!.runOnUiThread {
                        Toast.makeText(context, getString(R.string.connection_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        if (BluetoothServiceProvider.getBluetoothService().isBluetoothEnabled()) {
            showBluetoothDevices()
        }
    }

    private fun showBluetoothDevices() {
        BluetoothServiceProvider.getBluetoothService().setup()
        viewModel.nearbyDevices = MutableLiveData()
        viewModel.nearbyDevices.value = mutableListOf()

        if (this.context!!.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            activity!!.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }

        viewModel.nearbyDevices.observe(viewLifecycleOwner, Observer { deviceList ->
            BluetoothServiceProvider.getBluetoothService().discoverDevices({ device ->
                if (deviceList.find { d -> d.address == device.address } == null) {
                    Log.println(Log.INFO, "Found Nearby Device: ", device.name)
                    deviceList.add(RealBluetoothDevice(device))
                    val adapterNearby = BluetoothDeviceAdapter(this.context!!, deviceList)
                    lvNearbyDevices.adapter = adapterNearby
                    lvNearbyDevices.addOnItemTouchListener(RecyclerItemClickListener(context, lvNearbyDevices, object : RecyclerItemClickListener.OnItemClickListener {
                        override fun onItemClick(view: View?, position: Int) {
                            connectToSelectedDevice(
                                adapterNearby.getDeviceAt(position),
                                lvNearbyDevices[position].findViewById<ProgressBar>(R.id.loading_spinner)
                            )
                        }
        
                        override fun onLongItemClick(view: View?, position: Int) {}
                    }))
                }
            }, {})
        })

        val adapterPaired = BluetoothDeviceAdapter(this.context!!, viewModel.getPairedDevices())
        lvPairedDevices.adapter = adapterPaired
        lvPairedDevices.addOnItemTouchListener(RecyclerItemClickListener(context, lvPairedDevices, object : RecyclerItemClickListener.OnItemClickListener {
            override fun onItemClick(view: View?, position: Int) {
                connectToSelectedDevice(
                    adapterPaired.getDeviceAt(position),
                    lvPairedDevices[position].findViewById<ProgressBar>(R.id.loading_spinner)
                )
            }
        
            override fun onLongItemClick(view: View?, position: Int) {}
        }))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                if (bluetoothAdapter!!.isEnabled) {
                    Toast.makeText(activity, getString(R.string.bluetooth_enabled), Toast.LENGTH_SHORT).show()
                    showBluetoothDevices()
                } else {
                    Toast.makeText(activity, getString(R.string.bluetooth_disabled), Toast.LENGTH_SHORT).show()
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(activity, getString(R.string.bluetooth_enable_cancelled), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectToSelectedDevice(
        device: IBluetoothDevice,
        loadingIndicator: ProgressBar
    ) {
        synchronized(this) {
            if (currentConnectingIndicator == null) {
                loadingIndicator.visibility = View.VISIBLE
                currentConnectingIndicator = loadingIndicator
            }
        }

        pullToRefreshContainer.isRefreshing = false

        Log.d("Connecting", "Clicked on device '${device.name}'")
        if(!BluetoothServiceProvider.getBluetoothService().connectToDevice(device)) {
            Toast.makeText(context, getString(R.string.connect_to_failed, device.name),
                Toast.LENGTH_LONG).show()
            Log.e("Connecting", getString(R.string.connect_to_failed, device.name))
            synchronized(this) {
                currentConnectingIndicator = null
                loadingIndicator.visibility = View.GONE
            }
        } else {
            Log.i("Connecting", "Initialising connection to device '${device.name}' succeeded")
        }
    }
}
