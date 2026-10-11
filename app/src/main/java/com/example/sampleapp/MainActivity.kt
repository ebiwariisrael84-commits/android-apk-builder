package com.example.sampleapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val p by lazy { getSharedPreferences("myinvest_local", MODE_PRIVATE) }
    private val cloud by lazy { SupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }
    @Volatile private var cloudAccessToken: String? = null
    @Volatile private var cloudRefreshToken: String? = null
    @Volatile private var cloudUserId: String? = null
    @Volatile private var cloudRole: String = "investor"
    private var selectedIdImageUri: Uri? = null
    private var selectedFaceImageUri: Uri? = null
    private val ink=Color.rgb(26,39,55); private val muted=Color.rgb(111,124,139)
    private val green=Color.rgb(15,130,88); private val pale=Color.rgb(235,246,240); private val bg=Color.rgb(247,249,248)
    private var marketValues=floatArrayOf(); private var marketStatus="Loading market data…"; private var marketChange=""
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg; window.navigationBarColor=Color.WHITE
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        auth(false)
        val savedRefresh=p.getString("cloud_refresh_token",null)
        if(savedRefresh!=null && cloud.isConfigured()) thread {
            try {
                val session=cloud.refresh(savedRefresh)
                val profile=cloud.fetchProfile(session.accessToken,session.userId)
                runOnUiThread {
                    saveSession(session)
                    if(profile.optString("account_status")=="approved") {
                        p.edit().putString("account_name",profile.optString("full_name","Investor")).putString("account_email",session.email).putBoolean("logged",true).apply()
                        cloudRole=profile.optString("role","investor"); loadCloudWalletThenDashboard()
                    } else { p.edit().remove("cloud_access_token").remove("cloud_refresh_token").putBoolean("logged",false).apply(); auth(false); toast("Your account is "+profile.optString("account_status","pending")+". Dashboard access requires administrator approval.") }
                }
            } catch (_:Exception) { runOnUiThread { p.edit().putBoolean("logged",false).apply(); auth(false); toast("Could not reconnect to your cloud account. Check internet and sign in again to retry.") } }
        }
    }
    private fun saveSession(session:SupabaseClient.Session) {
        cloudAccessToken=session.accessToken; cloudRefreshToken=session.refreshToken; cloudUserId=session.userId
        p.edit().putString("cloud_access_token",session.accessToken).putString("cloud_refresh_token",session.refreshToken).putString("cloud_user_id",session.userId).apply()
    }
    private fun loadCloudWalletThenDashboard() {
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null)
        val uid=cloudUserId ?: p.getString("cloud_user_id",null)
        if(token==null || uid==null) { auth(false); return }
        thread {
            try {
                val wallet=cloud.fetchWallet(token,uid)
                runOnUiThread {
                    p.edit().putFloat("deposited",wallet.optDouble("deposited_amount",0.0).toFloat())
                        .putFloat("capital",wallet.optDouble("capital_amount",0.0).toFloat())
                        .putFloat("profits",wallet.optDouble("profit_amount",0.0).toFloat())
                        .putFloat("withdrawals",wallet.optDouble("withdrawals_amount",0.0).toFloat()).apply()
                    dashboard()
                }
            } catch(e:Exception) { runOnUiThread { toast("Cloud account data could not be loaded: "+(e.message ?: "network error")); auth(false) } }
        }
    }

    private fun root(title:String,sub:String?=null):Pair<ScrollView,LinearLayout>{
        val page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(28));setBackgroundColor(bg)}
        val scroll=ScrollView(this).apply{addView(page)}
        page.addView(txt("MY INVEST",13,green,true));page.addView(txt(title,26,ink,true,8));if(sub!=null)page.addView(txt(sub,14,muted,false,5))
        return scroll to page
    }
    private fun auth(register:Boolean){
        val(scroll,page)=root(if(register)"Create account" else "Welcome back",if(register)"Create your MY INVEST account" else "Sign in securely to your cloud account")
        val name=input("Full name").apply{visibility=if(register)View.VISIBLE else View.GONE}
        val email=input("Email address",InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass=input("Password",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        page.addView(card().apply{
            addView(name);addView(email,mp(12));addView(pass,mp(12))
            addView(btn(if(register)"Create account" else "Sign in",true){
                val n=name.text.toString().trim();val e=email.text.toString().trim().lowercase(Locale.ROOT);val pw=pass.text.toString()
                when {
                    !cloud.isConfigured()->toast("Cloud connection is not configured. Please rebuild after adding the Supabase GitHub secrets.")
                    register&&n.length<2->toast("Enter your full name.")
                    !e.contains("@")||!e.contains(".")->toast("Enter a valid email.")
                    pw.length<8->toast("Password must contain at least 8 characters.")
                    else->{
                        val action=btn(if(register)"Create account" else "Sign in",true){}
                        thread {
                            try {
                                if(register) {
                                    val response=cloud.signUp(e,pw,n)
                                    val obj=try{response.json()}catch(_:Exception){JSONObject()}
                                    val access=obj.optString("access_token")
                                    val user=obj.optJSONObject("user") ?: obj.optJSONObject("data")?.optJSONObject("user") ?: if(obj.has("id")) obj else null
                                    if(access.isBlank() || user==null) {
                                        runOnUiThread { toast("Registration submitted. Check your email to confirm the account, then sign in. Your profile must be approved by the administrator.") }
                                    } else {
                                        val session=SupabaseClient.Session(access,obj.optString("refresh_token"),user.optString("id"),user.optString("email",e))
                                        val profile=cloud.fetchProfile(session.accessToken,session.userId)
                                        runOnUiThread {
                                            saveSession(session)
                                            if(profile.optString("account_status")=="approved") {
                                                cloudRole=profile.optString("role","investor")
                                                p.edit().putString("account_name",profile.optString("full_name",n)).putString("account_email",e).putBoolean("logged",true).apply()
                                                loadCloudWalletThenDashboard()
                                            } else { toast("Account created. Administrator approval is required before dashboard access."); auth(false) }
                                        }
                                    }
                                } else {
                                    val session=cloud.signIn(e,pw)
                                    val profile=cloud.fetchProfile(session.accessToken,session.userId)
                                    runOnUiThread {
                                        if(profile.optString("account_status")!="approved") {
                                            toast("Your account is "+profile.optString("account_status","pending")+". Please wait for administrator approval.")
                                        } else {
                                            saveSession(session);cloudRole=profile.optString("role","investor")
                                            p.edit().putString("account_name",profile.optString("full_name","Investor")).putString("account_email",session.email).putBoolean("logged",true).apply()
                                            loadCloudWalletThenDashboard()
                                        }
                                    }
                                }
                            } catch(e:Exception) {
                                runOnUiThread { toast("Cloud connection failed: "+(e.message ?: "Please try again.")) }
                            }
                        }
                    }
                }
            },mp(18))
            addView(btn(if(register)"Already registered? Sign in" else "Create a new account",false){auth(!register)},mp(8))
        },mp(24))
        page.addView(txt("Accounts and balances require the configured Supabase cloud service. New accounts need administrator approval.",12,muted,false,18))
        setContentView(scroll)
    }
    private fun dashboard(){
        val first=p.getString("account_name","Investor")?.substringBefore(" ")?: "Investor"
        val(scroll,page)=root("Your dashboard","Welcome, "+first)
        val deposited=amt("deposited");val capital=amt("capital");val profits=amt("profits");val withdrawals=amt("withdrawals")
        val available=(deposited-capital-withdrawals).coerceAtLeast(0f)
        page.addView(card().apply{
            setBackgroundColor(ink);addView(txt("PROFIT",11,Color.rgb(173,239,208),true));addView(txt(money(profits),36,Color.WHITE,true,8));addView(txt("Recorded profit balance • USD",12,Color.rgb(208,225,216),false,4))
            addView(View(this@MainActivity).apply{setBackgroundColor(Color.rgb(73,89,103));layoutParams=LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(18);bottomMargin=dp(12)}})
            addView(rowMetric("Deposited amount",money(deposited)));addView(rowMetric("Capital invested",money(capital)));addView(rowMetric("Available balance",money(available)));addView(rowMetric("Withdrawals",money(withdrawals)))
        },mp(22))
        page.addView(txt("Quick actions",19,ink,true,24))
        page.addView(LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;addView(btn("Withdraw",true){withdraw()},LinearLayout.LayoutParams(0,dp(48),1f));addView(gap(10),LinearLayout.LayoutParams(dp(10),1));addView(btn("Investment plans",false){plans()},LinearLayout.LayoutParams(0,dp(48),1f))},mp(12))
        page.addView(txt("S&P 500",19,ink,true,24))
        val market=card();market.addView(txt("S&P 500 index",16,ink,true));val status=txt(marketStatus,12,muted,false,4);market.addView(status)
        val chart=MarketLine(this);market.addView(chart,LinearLayout.LayoutParams(-1,dp(135)).apply{topMargin=dp(8)});val change=txt(marketChange,12,green,true,4);market.addView(change);page.addView(market,mp(12))
        loadMarket{s,v,c->marketStatus=s;marketValues=v;marketChange=c;status.text=s;change.text=c;chart.invalidate()}
        page.addView(txt("Your investment",19,ink,true,24))
        page.addView(card().apply{val active=p.getString("active_plan",null);addView(txt(active?:"No active plan",17,ink,true));addView(txt(if(active==null)"Choose a plan to get started." else "Selected plan",13,muted,false,5));addView(btn(if(active==null)"Explore plans" else "View plans",false){plans()},mp(12))},mp(12))
        page.addView(txt("Account",19,ink,true,24))
        page.addView(LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;addView(btn("Transactions",false){transactions()},LinearLayout.LayoutParams(0,dp(46),1f));addView(gap(8),LinearLayout.LayoutParams(dp(8),1));addView(btn("Profile",false){profile()},LinearLayout.LayoutParams(0,dp(46),1f))},mp(12))
        setContentView(scroll)
    }
    private data class Plan(val name:String,val icon:String,val min:Float,val tagline:String,val details:String)
    private val planList=listOf(Plan("Bronze","🥉",250f,"A simple starting tier","Portfolio overview and transaction tracking"),Plan("Silver","🥈",500f,"For growing portfolios","Portfolio tracking and plan history"),Plan("Gold","🥇",1000f,"For established investors","Expanded portfolio summaries"),Plan("Platinum","💎",5000f,"For larger portfolios","Full plan overview and account reporting"),Plan("Diamond","💠",10000f,"A premium investment tier","Enhanced portfolio reporting and history"),Plan("Elite","👑",25000f,"The highest membership tier","Premium account overview and reporting"))
    private fun plans(){
        val(scroll,page)=root("Investment plans","Choose the tier that fits your goals")
        planList.forEach{plan->page.addView(card().apply{
            addView(txt(plan.icon+"  "+plan.name,21,ink,true));addView(txt(plan.tagline,13,green,true,4));addView(txt("Investment amount: "+money(plan.min),16,ink,true,14));addView(txt(plan.details,13,muted,false,5));addView(txt("Fixed configured rate: 60% once, not weekly or monthly. Profit is not guaranteed and must reflect verified performance.",11,muted,false,10))
            addView(btn(if(p.getString("active_plan",null)==plan.name)"Current plan" else "Select "+plan.name,true){
                val available=(amt("deposited")-amt("capital")-amt("withdrawals")).coerceAtLeast(0f)
                when{p.getString("active_plan",null)==plan.name->toast(plan.name+" is your current plan.")
                    available<plan.min->toast("Available balance: "+money(available)+". "+plan.name+" requires "+money(plan.min)+". A connected funding service or administrator must update your balance.")
                    else->{
                        val token=cloudAccessToken ?: p.getString("cloud_access_token",null)
                        if(token==null) toast("Please sign in to your cloud account first.")
                        else thread {
                            try {
                                cloud.requestInvestment(token,plan.name.lowercase(Locale.ROOT),plan.min.toDouble())
                                runOnUiThread { p.edit().putString("active_plan",plan.name).apply();toast("Investment request submitted for administrator review. No funds are moved until approval.");loadCloudWalletThenDashboard() }
                            } catch(e:Exception) { runOnUiThread { toast("Investment request failed: "+(e.message ?: "Please try again.")) } }
                        }
                    }
                }
            },mp(14))
        },mp(14))}
        page.addView(btn("Back to dashboard",false){dashboard()},mp(18));setContentView(scroll)
    }
    private fun withdraw(){
        val(scroll,page)=root("Withdraw funds","Submit a withdrawal request")
        val available=(amt("deposited")-amt("capital")-amt("withdrawals")).coerceAtLeast(0f)
        page.addView(card().apply{
            addView(txt("Available balance",12,muted,true));addView(txt(money(available),25,ink,true,8))
            val value=input("Amount in USD",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL);addView(value,mp(16))
            addView(txt("Requests are sent to the cloud for administrator review. Submission does not transfer money.",12,muted,false,8))
            addView(btn("Submit withdrawal request",true){
                val v=value.text.toString().toFloatOrNull()
                when{v==null||v<=0f->toast("Enter a valid amount.");v>available->toast("The amount exceeds your available balance.");else->{
                    val token=cloudAccessToken ?: p.getString("cloud_access_token",null)
                    if(token==null) toast("Please sign in to your cloud account first.")
                    else thread {
                        try {
                            cloud.requestWithdrawal(token,v.toDouble())
                            runOnUiThread { toast("Withdrawal request submitted for administrator review. No money has been transferred.");loadCloudWalletThenDashboard() }
                        } catch(e:Exception) { runOnUiThread { toast("Withdrawal request failed: "+(e.message ?: "Please try again.")) } }
                    }
                }}
            },mp(16))
        },mp(22))
        page.addView(btn("Cancel",false){dashboard()},mp(12));setContentView(scroll)
    }
    private fun transactions(){
        val(scroll,page)=root("Transactions","Cloud-synced account activity")
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null)
        val uid=cloudUserId ?: p.getString("cloud_user_id",null)
        val status=txt("Loading transactions…",13,muted,false,12);page.addView(status)
        page.addView(btn("Back",false){dashboard()},mp(18))
        setContentView(scroll)
        if(token==null||uid==null){status.text="Please sign in to your cloud account.";return}
        thread{
            try{
                val rows=cloud.fetchTransactions(token,uid)
                runOnUiThread{
                    status.text=if(rows.length()==0)"No cloud transactions yet." else "Recent transactions"
                    for(i in 0 until rows.length()){
                        val item=rows.optJSONObject(i)?:continue
                        page.addView(card().apply{
                            addView(txt(item.optString("transaction_type").replace('_',' ').replaceFirstChar{it.uppercase()},15,ink,true))
                            addView(txt(item.optString("description"),12,muted,false,4))
                            addView(txt(money(item.optDouble("amount").toFloat()),16,green,true,8))
                            addView(txt("Status: "+item.optString("status"),12,muted,false,4))
                            addView(txt(item.optString("created_at"),11,muted,false,4))
                        },mp(10))
                    }
                }
            }catch(e:Exception){runOnUiThread{status.text="Unable to load cloud transactions: "+(e.message ?: "Cloud error")}}
        }
    }
    private fun profile(){
        val(scroll,page)=root("Your account","Personal details and identity verification")
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null)
        val uid=cloudUserId ?: p.getString("cloud_user_id",null)
        val fullName=input("Full legal name")
        val country=input("Country")
        val dob=input("Date of birth (YYYY-MM-DD)")
        val address=input("Residential address")
        val phone=input("Phone number")
        val profileStatus=txt("Loading your cloud profile…",12,muted,false,8)
        page.addView(card().apply{
            addView(txt(p.getString("account_name","Investor")?:"Investor",19,ink,true))
            addView(txt(p.getString("account_email","")?:"",14,muted,false,5))
            addView(profileStatus)
            addView(txt("Personal details",16,ink,true,14))
            addView(fullName,mp(8));addView(country,mp(8));addView(dob,mp(8));addView(address,mp(8));addView(phone,mp(8))
            addView(btn("Save personal details",true){
                if(token==null){toast("Please sign in again.");return@btn}
                val n=fullName.text.toString().trim()
                val c=country.text.toString().trim()
                val d=dob.text.toString().trim()
                val a=address.text.toString().trim()
                val ph=phone.text.toString().trim()
                if(n.length<2||c.isBlank()||!d.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))||a.isBlank()||ph.isBlank()){
                    toast("Complete all details and use YYYY-MM-DD for date of birth.")
                } else thread{
                    try{cloud.updateProfile(token,n,c,d,a,ph);runOnUiThread{toast("Personal details saved to the cloud.");profileStatus.text="Profile details saved."}}
                    catch(e:Exception){runOnUiThread{toast("Profile update failed: "+(e.message ?: "Cloud error"))}}
                }
            },mp(14))
        },mp(16))
        page.addView(txt("Identity verification",18,ink,true,18))
        page.addView(card().apply{
            addView(txt("Choose one image showing one side of your government-issued ID, and one clear face/passport photo. Images are uploaded to private cloud storage.",12,muted,false))
            addView(btn("Choose ID image",false){pickKycImage(1001)},mp(12))
            addView(txt(if(selectedIdImageUri==null)"No ID image selected" else "ID image selected",12,green,true,6))
            addView(btn("Choose face/passport photo",false){pickKycImage(1002)},mp(12))
            addView(txt(if(selectedFaceImageUri==null)"No face photo selected" else "Face photo selected",12,green,true,6))
            val docType=Spinner(this@MainActivity).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("National ID","Passport","Driver's licence","Voter's card"))}
            addView(txt("ID document type",13,muted,true,12));addView(docType,mp(4))
            addView(btn("Submit identity verification",true){
                val idUri=selectedIdImageUri;val faceUri=selectedFaceImageUri
                if(token==null||uid==null)toast("Please sign in again.")
                else if(idUri==null||faceUri==null)toast("Choose both the ID image and face photo.")
                else thread{
                    try{
                        val idBytes=contentResolver.openInputStream(idUri)?.use{it.readBytes()}?:throw Exception("Could not read ID image.")
                        val faceBytes=contentResolver.openInputStream(faceUri)?.use{it.readBytes()}?:throw Exception("Could not read face image.")
                        val idPath=cloud.uploadKycImage(token,uid,"id",idBytes,contentResolver.getType(idUri)?: "image/jpeg")
                        val facePath=cloud.uploadKycImage(token,uid,"face",faceBytes,contentResolver.getType(faceUri)?: "image/jpeg")
                        cloud.submitKyc(token,uid,docType.selectedItem.toString(),idPath,facePath)
                        runOnUiThread{toast("Identity documents submitted for administrator review.");selectedIdImageUri=null;selectedFaceImageUri=null;profile()}
                    }catch(e:Exception){runOnUiThread{toast("Identity submission failed: "+(e.message ?: "Cloud error"))}}
                }
            },mp(14))
        },mp(10))
        if(cloudRole=="admin") page.addView(btn("Administrator tools",true){adminDashboard()},mp(12))
        page.addView(btn("Refresh cloud account",false){loadCloudWalletThenDashboard()},mp(12))
        page.addView(btn("Sign out",false){
            val current=cloudAccessToken
            p.edit().remove("cloud_access_token").remove("cloud_refresh_token").remove("cloud_user_id").putBoolean("logged",false).apply()
            cloudAccessToken=null;cloudRefreshToken=null;cloudUserId=null
            thread{try{if(current!=null)cloud.signOut(current)}catch(_:Exception){}}
            auth(false)
        },mp(18))
        page.addView(btn("Back to dashboard",false){dashboard()},mp(10))
        setContentView(scroll)
        if(token!=null&&uid!=null) thread{
            try{
                val data=cloud.fetchProfileDetails(token,uid)
                runOnUiThread{
                    fullName.setText(data.optString("full_name"))
                    country.setText(data.optString("country"))
                    dob.setText(data.optString("date_of_birth"))
                    address.setText(data.optString("address"))
                    phone.setText(data.optString("phone"))
                    profileStatus.text="Account status: "+data.optString("account_status")
                }
            }catch(e:Exception){runOnUiThread{profileStatus.text="Could not load profile: "+(e.message ?: "Cloud error")}}
        }
    }
    private fun pickKycImage(requestCode:Int){
        val intent=Intent(Intent.ACTION_GET_CONTENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)}
        startActivityForResult(Intent.createChooser(intent,"Choose image"),requestCode)
    }
    @Deprecated("Use the image picker result API")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode==RESULT_OK&&data?.data!=null){
            if(requestCode==1001) selectedIdImageUri=data.data
            if(requestCode==1002) selectedFaceImageUri=data.data
            profile()
        }
    }
    private fun adminDashboard(query:String=""){
        if(cloudRole!="admin"){toast("Administrator access required.");return}
        val(scroll,page)=root("Administrator tools","Cloud-managed accounts and audited balance adjustments")
        val search=input("Search users by email",InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply{setText(query)}
        page.addView(card().apply{
            addView(search)
            addView(btn("Search users",true){adminDashboard(search.text.toString().trim())},mp(10))
        },mp(16))
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null)
        if(token==null){page.addView(txt("Please sign in again.",13,muted,false,12));setContentView(scroll);return}
        val loading=txt("Loading cloud users…",13,muted,false,14);page.addView(loading)
        setContentView(scroll)
        thread{
            try{
                val users=cloud.searchUsers(token,query)
                runOnUiThread{
                    loading.text=if(users.length()==0)"No users found." else "Users ("+users.length()+")"
                    for(i in 0 until users.length()){
                        val user=users.optJSONObject(i)?:continue
                        val id=user.optString("id")
                        page.addView(card().apply{
                            addView(txt(user.optString("full_name","Investor"),16,ink,true))
                            addView(txt(user.optString("email"),12,muted,false,4))
                            addView(txt("Role: "+user.optString("role")+" • Status: "+user.optString("account_status"),12,green,true,8))
                            addView(btn("Approve account",false){
                                changeUserStatus(id,"approved","Approved by administrator",query)
                            },mp(8))
                            addView(btn("Require resubmission",false){
                                changeUserStatus(id,"resubmission_required","Please resubmit required information",query)
                            },mp(6))
                            addView(btn("Suspend account",false){
                                changeUserStatus(id,"suspended","Suspended by administrator",query)
                            },mp(6))
                            addView(btn("Select for balance adjustment",true){
                                adminBalanceEditor(id,user.optString("email"),query)
                            },mp(8))
                        },mp(10))
                    }
                }
            }catch(e:Exception){runOnUiThread{loading.text="Unable to load users: "+(e.message ?: "Cloud error")}}
        }
        val investmentHeading=txt("Pending investment requests",18,ink,true,24)
        page.addView(investmentHeading)
        val investmentStatus=txt("Loading pending requests…",13,muted,false,8);page.addView(investmentStatus)
        thread{
            try{
                val pending=cloud.fetchPendingInvestments(token)
                runOnUiThread{
                    investmentStatus.text=if(pending.length()==0)"No pending investment requests." else "Requests ("+pending.length()+")"
                    for(i in 0 until pending.length()){
                        val inv=pending.optJSONObject(i)?:continue
                        val investmentId=inv.optString("id")
                        val investorId=inv.optString("user_id")
                        page.addView(card().apply{
                            addView(txt("Investment request",15,ink,true))
                            addView(txt("Investor ID: "+investorId,11,muted,false,4))
                            addView(txt("Principal: "+money(inv.optDouble("principal_amount").toFloat()),16,green,true,8))
                            addView(txt("Configured profit estimate: "+money(inv.optDouble("fixed_profit_amount").toFloat())+" (not guaranteed)",12,muted,false,4))
                            addView(btn("Approve investment",true){
                                reviewInvestment(investmentId,"active","Approved by administrator",query)
                            },mp(8))
                            addView(btn("Reject investment",false){
                                reviewInvestment(investmentId,"rejected","Rejected by administrator",query)
                            },mp(6))
                        },mp(10))
                    }
                }
            }catch(e:Exception){runOnUiThread{investmentStatus.text="Could not load investment requests: "+(e.message ?: "Cloud error")}}
        }
        val withdrawalHeading=txt("Pending withdrawal requests",18,ink,true,24)
        page.addView(withdrawalHeading)
        val withdrawalStatus=txt("Loading withdrawal requests…",13,muted,false,8);page.addView(withdrawalStatus)
        thread{
            try{
                val requests=cloud.fetchPendingWithdrawals(token)
                runOnUiThread{
                    withdrawalStatus.text=if(requests.length()==0)"No pending withdrawal requests." else "Requests ("+requests.length()+")"
                    for(i in 0 until requests.length()){
                        val item=requests.optJSONObject(i)?:continue
                        val txId=item.optString("id")
                        page.addView(card().apply{
                            addView(txt("Withdrawal request",15,ink,true))
                            addView(txt("Investor ID: "+item.optString("user_id"),11,muted,false,4))
                            addView(txt("Amount: "+money(item.optDouble("amount").toFloat()),16,green,true,8))
                            addView(btn("Approve request (payout still manual)",true){
                                reviewWithdrawal(txId,"approved","Approved; payout must be completed manually",query)
                            },mp(8))
                            addView(btn("Reject request",false){
                                reviewWithdrawal(txId,"rejected","Rejected by administrator",query)
                            },mp(6))
                        },mp(10))
                    }
                }
            }catch(e:Exception){runOnUiThread{withdrawalStatus.text="Could not load withdrawals: "+(e.message ?: "Cloud error")}}
        }
        val kycHeading=txt("Pending identity verification",18,ink,true,24)
        page.addView(kycHeading)
        val kycStatus=txt("Loading identity submissions…",13,muted,false,8);page.addView(kycStatus)
        thread{
            try{
                val submissions=cloud.fetchPendingKyc(token)
                runOnUiThread{
                    kycStatus.text=if(submissions.length()==0)"No pending identity submissions." else "Submissions ("+submissions.length()+")"
                    for(i in 0 until submissions.length()){
                        val item=submissions.optJSONObject(i)?:continue
                        val sid=item.optString("id")
                        val idPath=item.optString("id_image_path")
                        val facePath=item.optString("face_image_path")
                        page.addView(card().apply{
                            addView(txt(item.optString("document_type")+" • Identity verification",15,ink,true))
                            addView(txt("Investor ID: "+item.optString("user_id"),11,muted,false,4))
                            addView(btn("View ID image (private link)",false){openKycDocument(idPath)},mp(8))
                            addView(btn("View face photo (private link)",false){openKycDocument(facePath)},mp(6))
                            addView(btn("Approve identity",true){reviewKyc(sid,"approved","Verified by administrator",query)},mp(8))
                            addView(btn("Reject identity",false){reviewKyc(sid,"rejected","Identity documents rejected",query)},mp(6))
                            addView(btn("Request resubmission",false){reviewKyc(sid,"resubmission_required","Please upload clearer identity images",query)},mp(6))
                        },mp(10))
                    }
                }
            }catch(e:Exception){runOnUiThread{kycStatus.text="Could not load identity submissions: "+(e.message ?: "Cloud error")}}
        }
        page.addView(btn("Back to profile",false){profile()},mp(18))
    }
    private fun reviewWithdrawal(id:String,decision:String,reason:String,query:String){
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null) ?: run{toast("Please sign in again.");return}
        thread{
            try{
                cloud.adminReviewWithdrawal(token,id,decision,reason)
                runOnUiThread{
                    toast(if(decision=="approved")"Withdrawal approved. Payment must still be sent manually." else "Withdrawal rejected.")
                    adminDashboard(query)
                }
            }catch(e:Exception){runOnUiThread{toast("Withdrawal review failed: "+(e.message ?: "Cloud error"))}}
        }
    }
    private fun openKycDocument(path:String){
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null) ?: run{toast("Please sign in again.");return}
        thread{
            try{
                val url=cloud.signedKycUrl(token,path)
                runOnUiThread{try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch(_:Exception){toast("Could not open the private document.")}}
            }catch(e:Exception){runOnUiThread{toast("Could not open document: "+(e.message ?: "Cloud error"))}}
        }
    }
    private fun reviewKyc(id:String,status:String,reason:String,query:String){
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null) ?: run{toast("Please sign in again.");return}
        thread{
            try{cloud.reviewKyc(token,id,status,reason);runOnUiThread{toast("Identity submission marked "+status+".");adminDashboard(query)}}
            catch(e:Exception){runOnUiThread{toast("KYC review failed: "+(e.message ?: "Cloud error"))}}
        }
    }
    private fun reviewInvestment(id:String,decision:String,reason:String,query:String){
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null) ?: run{toast("Please sign in again.");return}
        thread{
            try{
                cloud.adminReviewInvestment(token,id,decision,reason)
                runOnUiThread{toast("Investment request "+decision+".");adminDashboard(query)}
            }catch(e:Exception){runOnUiThread{toast("Investment review failed: "+(e.message ?: "Cloud error"))}}
        }
    }
    private fun changeUserStatus(userId:String,status:String,reason:String,query:String){
        val token=cloudAccessToken ?: p.getString("cloud_access_token",null) ?: run{toast("Please sign in again.");return}
        thread{
            try{
                cloud.adminSetProfileStatus(token,userId,status,reason)
                runOnUiThread{toast("Account status updated.");adminDashboard(query)}
            }catch(e:Exception){runOnUiThread{toast("Status update failed: "+(e.message ?: "Cloud error"))}}
        }
    }
    private fun adminBalanceEditor(userId:String,email:String,query:String){
        val(scroll,page)=root("Adjust account balance",email)
        val field=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                arrayOf("deposited_amount","capital_amount","profit_amount","withdrawals_amount"))
        }
        val amount=input("New amount in USD",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val reason=input("Reason for audit log")
        page.addView(card().apply{
            addView(txt("Balance field",13,muted,true));addView(field,mp(6))
            addView(txt("New amount (USD)",13,muted,true,12));addView(amount,mp(6))
            addView(txt("Reason (required)",13,muted,true,12));addView(reason,mp(6))
            addView(btn("Save cloud balance",true){
                val v=amount.text.toString().toDoubleOrNull()
                val why=reason.text.toString().trim()
                when{v==null||v<0->toast("Enter a valid non-negative amount.");why.length<4->toast("Enter a reason for the audit log.");else->{
                    val token=cloudAccessToken ?: p.getString("cloud_access_token",null)
                    if(token==null)toast("Please sign in again.")
                    else thread{
                        try{
                            cloud.adminAdjustWallet(token,userId,field.selectedItem.toString(),v,why)
                            runOnUiThread{toast("Cloud balance updated and audit logged.");adminDashboard(query)}
                        }catch(e:Exception){runOnUiThread{toast("Balance update failed: "+(e.message ?: "Cloud error"))}}
                    }
                }}
            },mp(16))
        },mp(20))
        page.addView(btn("Cancel",false){adminDashboard(query)},mp(12))
        setContentView(scroll)
    }

    private fun manageBalances(){
        val(scroll,page)=root("Balance management","Update the figures shown in this local account")
        page.addView(card().apply{
            val deposited=input("Total deposited in USD",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply{setText(amt("deposited").toString())}
            val profits=input("Profits in USD",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply{setText(amt("profits").toString())}
            addView(txt("Total deposited",13,muted,true));addView(deposited,mp(6))
            addView(txt("Profits",13,muted,true,14));addView(profits,mp(6))
            addView(btn("Save balances",true){
                val d=deposited.text.toString().toFloatOrNull();val pr=profits.text.toString().toFloatOrNull()
                when{d==null||pr==null||d<0||pr<0->toast("Enter valid non-negative amounts.");else->{p.edit().putFloat("deposited",d).putFloat("profits",pr).apply();addTx("Balance updated","Account balance figures",d);toast("Balances updated.");dashboard()}}
            },mp(18))
        },mp(20))
        page.addView(btn("Cancel",false){profile()},mp(12));setContentView(scroll)
    }

    private fun addTx(type:String,detail:String,value:Float){
        val a=try{org.json.JSONArray(p.getString("transactions","[]"))}catch(_:Exception){org.json.JSONArray()}
        a.put(JSONObject().put("type",type).put("detail",detail).put("amount",value.toDouble()).put("date",java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(java.util.Date())))
        p.edit().putString("transactions",a.toString()).apply()
    }
    private fun loadMarket(done:(String,FloatArray,String)->Unit){thread{
        try{
            val c=URL("https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC?range=1mo&interval=1d").openConnection() as HttpURLConnection
            c.connectTimeout=7000;c.readTimeout=7000;c.setRequestProperty("User-Agent","Mozilla/5.0")
            val body=c.inputStream.bufferedReader().use{it.readText()};c.disconnect()
            val result=JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val closes=result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
            val list=mutableListOf<Float>();for(i in 0 until closes.length())if(!closes.isNull(i))list.add(closes.getDouble(i).toFloat())
            val values=list.toFloatArray();val first=values.firstOrNull()?:0f;val last=values.lastOrNull()?:0f;val pct=if(first>0)(last-first)/first*100 else 0f
            runOnUiThread{done("Latest available market data • "+String.format(Locale.US,"%.2f",last),values,"1-month change: "+String.format(Locale.US,"%+.2f%%",pct))}
        }catch(_:Exception){runOnUiThread{done("Market data unavailable. Check internet connection.",floatArrayOf(),"")}}
    }}
    private inner class MarketLine(context:android.content.Context):View(context){
        private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=green;strokeWidth=dp(3).toFloat();style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        private val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(229,235,231);strokeWidth=dp(1).toFloat()}
        override fun onDraw(canvas:Canvas){super.onDraw(canvas);val l=dp(4).toFloat();val r=width-dp(4).toFloat();val t=dp(12).toFloat();val b=height-dp(12).toFloat()
            for(i in 0..3)canvas.drawLine(l,t+(b-t)*i/3f,r,t+(b-t)*i/3f,grid)
            val v=marketValues;if(v.size<2)return;val mn=v.minOrNull()?:return;val mx=v.maxOrNull()?:return;val range=(mx-mn).takeIf{it>0}?:1f;val path=Path()
            v.forEachIndexed{i,n->val x=l+(r-l)*i/(v.size-1);val y=b-(n-mn)/range*(b-t);if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};canvas.drawPath(path,line)
        }
    }
    private fun amt(key:String)=p.getFloat(key,0f)
    private fun money(v:Float)="$"+String.format(Locale.US,"%,.2f",v)
    private fun hash(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun input(h:String,type:Int=InputType.TYPE_CLASS_TEXT)=EditText(this).apply{hint=h;textSize=15f;setSingleLine(true);inputType=type;setPadding(dp(14),dp(12),dp(14),dp(12));setBackgroundColor(Color.WHITE)}
    private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(18));setBackgroundColor(Color.WHITE);elevation=dp(2).toFloat()}
    private fun rowMetric(label:String,value:String)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;addView(txt(label,13,Color.rgb(218,225,232),false),LinearLayout.LayoutParams(0,-2,1f));addView(txt(value,15,Color.WHITE,true));setPadding(0,dp(5),0,dp(5))}
    private fun txt(s:String,size:Int,color:Int,bold:Boolean,top:Int=0)=TextView(this).apply{text=s;textSize=size.toFloat();setTextColor(color);if(bold)typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD);if(top>0)setPadding(0,dp(top),0,0)}
    private fun btn(label:String,primary:Boolean,click:()->Unit)=Button(this).apply{text=label;textSize=13f;isAllCaps=false;setTextColor(if(primary)Color.WHITE else green);backgroundTintList=android.content.res.ColorStateList.valueOf(if(primary)green else pale);setOnClickListener{click()}}
    private fun gap(w:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(w),1)}
    private fun mp(top:Int)=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}