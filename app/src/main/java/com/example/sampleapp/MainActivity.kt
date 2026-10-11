package com.example.sampleapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
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
    private val ink=Color.rgb(26,39,55); private val muted=Color.rgb(111,124,139)
    private val green=Color.rgb(15,130,88); private val pale=Color.rgb(235,246,240); private val bg=Color.rgb(247,249,248)
    private var marketValues=floatArrayOf(); private var marketStatus="Loading market data…"; private var marketChange=""
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); window.statusBarColor=bg; window.navigationBarColor=Color.WHITE; window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if(p.getBoolean("logged",false)) dashboard() else auth(false) }

    private fun root(title:String,sub:String?=null):Pair<ScrollView,LinearLayout>{
        val page=LinearLayout(this).apply{orientation=1;setPadding(dp(20),dp(20),dp(20),dp(28));setBackgroundColor(bg)}
        val scroll=ScrollView(this).apply{addView(page)}
        page.addView(txt("MY INVEST",13,green,true));page.addView(txt(title,26,ink,true,8));if(sub!=null)page.addView(txt(sub,14,muted,false,5))
        return scroll to page
    }
    private fun auth(register:Boolean){
        val(scroll,page)=root(if(register)"Create account" else "Welcome back",if(register)"Create your personal account" else "Sign in to your account")
        val name=input("Full name").apply{visibility=if(register)View.VISIBLE else View.GONE}
        val email=input("Email address",InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass=input("Password",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        page.addView(card().apply{
            addView(name);addView(email,mp(12));addView(pass,mp(12))
            addView(btn(if(register)"Create account" else "Sign in",true){
                val n=name.text.toString().trim();val e=email.text.toString().trim().lowercase(Locale.ROOT);val pw=pass.text.toString()
                when{
                    register&&n.length<2->toast("Enter your full name.")
                    !e.contains("@")||!e.contains(".")->toast("Enter a valid email.")
                    pw.length<6->toast("Password must contain at least 6 characters.")
                    register&&p.getString("account_email",null)!=null->toast("A local account already exists. Sign in or clear app data to create another.")
                    register->{p.edit().putString("account_name",n).putString("account_email",e).putString("password_hash",hash(pw)).putBoolean("logged",true).putFloat("deposited",0f).putFloat("capital",0f).putFloat("profits",0f).putFloat("withdrawals",0f).putString("transactions","[]").apply();dashboard()}
                    p.getString("account_email","")!=e||p.getString("password_hash","")!=hash(pw)->toast("Email or password is incorrect.")
                    else->{p.edit().putBoolean("logged",true).apply();dashboard()}
                }
            },mp(18))
            addView(btn(if(register)"Already registered? Sign in" else "Create a new account",false){auth(!register)},mp(8))
        },mp(24))
        page.addView(txt("Account information is stored locally on this device in this build.",12,muted,false,18));setContentView(scroll)
    }
    private fun dashboard(){
        val first=p.getString("account_name","Investor")?.substringBefore(" ")?: "Investor"
        val(scroll,page)=root("Your dashboard","Welcome, "+first)
        val deposited=amt("deposited");val capital=amt("capital");val profits=amt("profits");val withdrawals=amt("withdrawals")
        val available=(deposited-capital-withdrawals).coerceAtLeast(0f)
        page.addView(card().apply{
            setBackgroundColor(ink);addView(txt("TOTAL DEPOSITED",11,Color.LTGRAY,true));addView(txt(money(deposited),30,Color.WHITE,true,10));addView(txt("Recorded deposited amount",12,Color.rgb(208,225,216),false,4))
            addView(View(this@MainActivity).apply{setBackgroundColor(Color.rgb(73,89,103));layoutParams=LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(18);bottomMargin=dp(12)}})
            addView(rowMetric("Available balance",money(available)));addView(rowMetric("Capital invested",money(capital)));addView(rowMetric("Profits",money(profits)));addView(rowMetric("Withdrawals",money(withdrawals))
        },mp(22))
        page.addView(txt("Quick actions",19,ink,true,24))
        page.addView(LinearLayout(this).apply{orientation=0;addView(btn("Withdraw",true){withdraw()},LinearLayout.LayoutParams(0,dp(48),1f));addView(gap(10),LinearLayout.LayoutParams(dp(10),1));addView(btn("Investment plans",false){plans()},LinearLayout.LayoutParams(0,dp(48),1f)},mp(12))
        page.addView(txt("S&P 500",19,ink,true,24))
        val market=card();market.addView(txt("S&P 500 index",16,ink,true));val status=txt(marketStatus,12,muted,false,4);market.addView(status)
        val chart=MarketLine(this);market.addView(chart,LinearLayout.LayoutParams(-1,dp(135)).apply{topMargin=dp(8)});val change=txt(marketChange,12,green,true,4);market.addView(change);page.addView(market,mp(12))
        loadMarket{s,v,c->marketStatus=s;marketValues=v;marketChange=c;status.text=s;change.text=c;chart.invalidate()}
        page.addView(txt("Your investment",19,ink,true,24))
        page.addView(card().apply{val active=p.getString("active_plan",null);addView(txt(active?:"No active plan",17,ink,true));addView(txt(if(active==null)"Choose a plan to get started." else "Selected plan",13,muted,false,5));addView(btn(if(active==null)"Explore plans" else "View plans",false){plans()},mp(12))},mp(12))
        page.addView(txt("Account",19,ink,true,24))
        page.addView(LinearLayout(this).apply{orientation=0;addView(btn("Transactions",false){transactions()},LinearLayout.LayoutParams(0,dp(46),1f));addView(gap(8),LinearLayout.LayoutParams(dp(8),1));addView(btn("Profile",false){profile()},LinearLayout.LayoutParams(0,dp(46),1f)},mp(12))
        setContentView(scroll)
    }
    private data class Plan(val name:String,val min:Float,val tagline:String,val details:String)
    private val planList=listOf(Plan("Bronze",50f,"A simple starting tier","Basic portfolio and transaction tracking"),Plan("Silver",250f,"For growing portfolios","Portfolio tracking and plan history"),Plan("Gold",1000f,"For established investors","Expanded portfolio summaries"),Plan("Platinum",5000f,"For larger portfolios","Full plan overview and account reporting"))
    private fun plans(){
        val(scroll,page)=root("Investment plans","Choose the tier that fits your goals")
        planList.forEach{plan->page.addView(card().apply{
            addView(txt(plan.name,20,ink,true));addView(txt(plan.tagline,13,green,true,4));addView(txt("Minimum capital: "+money(plan.min),15,ink,true,14));addView(txt(plan.details,13,muted,false,5))
            addView(btn(if(p.getString("active_plan",null)==plan.name)"Current plan" else "Select "+plan.name,true){
                val available=(amt("deposited")-amt("capital")-amt("withdrawals")).coerceAtLeast(0f)
                when{p.getString("active_plan",null)==plan.name->toast(plan.name+" is your current plan.")
                    available<plan.min->toast("Available balance: "+money(available)+". "+plan.name+" requires "+money(plan.min)+". A connected funding service or administrator must update your balance.")
                    else->{p.edit().putFloat("capital",amt("capital")+plan.min).putString("active_plan",plan.name).apply();addTx("Plan selected",plan.name,plan.min);dashboard()}
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
            addView(txt("Requests are saved locally; this build does not transfer money.",12,muted,false,8))
            addView(btn("Submit withdrawal request",true){
                val v=value.text.toString().toFloatOrNull()
                when{v==null||v<=0f->toast("Enter a valid amount.");v>available->toast("The amount exceeds your available balance.");else->{p.edit().putFloat("withdrawals",amt("withdrawals")+v).apply();addTx("Withdrawal request","Pending review",v);toast("Withdrawal request saved.");dashboard()}}
            },mp(16))
        },mp(22))
        page.addView(btn("Cancel",false){dashboard()},mp(12));setContentView(scroll)
    }
    private fun transactions(){
        val(scroll,page)=root("Transactions","Recorded account activity")
        val a=try{org.json.JSONArray(p.getString("transactions","[]"))}catch(_:Exception){org.json.JSONArray()}
        if(a.length()==0)page.addView(card().apply{addView(txt("No transactions yet",16,ink,true));addView(txt("Activity will appear here.",13,muted,false,5))},mp(20))
        else for(i in a.length()-1 downTo 0){val item=a.optJSONObject(i)?:continue;page.addView(card().apply{addView(txt(item.optString("type"),15,ink,true));addView(txt(item.optString("detail"),12,muted,false,4));addView(txt(money(item.optDouble("amount").toFloat()),16,green,true,8));addView(txt(item.optString("date"),11,muted,false,4)},mp(10))}
        page.addView(btn("Back",false){dashboard()},mp(18));setContentView(scroll)
    }
    private fun profile(){
        val(scroll,page)=root("Your account","Personal details and settings")
        page.addView(card().apply{addView(txt(p.getString("account_name","Investor")?:"Investor",19,ink,true));addView(txt(p.getString("account_email","")?:"",14,muted,false,5));addView(txt("Account data is stored locally on this device.",12,muted,false,14));addView(btn("Sign out",false){p.edit().putBoolean("logged",false).apply();auth(false)},mp(18))},mp(20))
        page.addView(btn("Back to dashboard",true){dashboard()},mp(14));setContentView(scroll)
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
    private fun card()=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(18),dp(18),dp(18));setBackgroundColor(Color.WHITE);elevation=dp(2).toFloat()}
    private fun rowMetric(label:String,value:String)=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER_VERTICAL;addView(txt(label,13,Color.rgb(218,225,232),false),LinearLayout.LayoutParams(0,-2,1f));addView(txt(value,15,Color.WHITE,true));setPadding(0,dp(5),0,dp(5))}
    private fun txt(s:String,size:Int,color:Int,bold:Boolean,top:Int=0)=TextView(this).apply{text=s;textSize=size.toFloat();setTextColor(color);if(bold)typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD);if(top>0)setPadding(0,dp(top),0,0)}
    private fun btn(label:String,primary:Boolean,click:()->Unit)=Button(this).apply{text=label;textSize=13f;isAllCaps=false;setTextColor(if(primary)Color.WHITE else green);backgroundTintList=android.content.res.ColorStateList.valueOf(if(primary)green else pale);setOnClickListener{click()}}
    private fun gap(w:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(w),1)}
    private fun mp(top:Int)=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}