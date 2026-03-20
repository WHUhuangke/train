#include <iostream>

    #include<vector>
    using namespace std;

    int main() {
    //记录查询的位置

    int k, q, max_len = 200001, max_query = 0, l=0, r, mod = 1000000007;
    long long slide_sum;
    cin >> k >> q;
    slide_sum = k;

    vector<long long> nums(max_len+1, 1), query(q+1);
    for(int i=0;i<q;i++){
    cin >> query[i];
    max_query = max_query > query[i] ? max_query : query[i];

    }
    // cout << "max q : " << max_query;
    for(int i=k;i<=max_query;i++){
    //先赋值，后滑动
    nums[i] = slide_sum % mod;
    slide_sum += mod;
    slide_sum -= nums[l];
    l++;
    slide_sum += nums[i];
    slide_sum %= mod;

    }
    for(int i = 0;i<q;i++){
    cout << nums[query[i]-1] << endl;
    }




    }